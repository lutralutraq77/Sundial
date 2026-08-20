package dev.danny.sundial.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import dev.danny.sundial.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OAuth 2.0 authorization-code + PKCE against Google, with no dependency on Play
 * Services: the consent screen opens in the system browser and the redirect comes back
 * to a short-lived HTTP server on 127.0.0.1.
 */
class AuthRepository(private val appContext: Context) {

    private val store = SecureStore(appContext)
    private val refreshMutex = Mutex()

    /**
     * Plain (non-secret) flags. A pending-sign-in marker lives here so that a process
     * killed while the user was over in the browser can explain what happened instead
     * of silently presenting an idle setup screen.
     */
    private val flags = appContext.getSharedPreferences("sundial_auth_flags", Context.MODE_PRIVATE)

    @Volatile
    private var pendingServer: LoopbackServer? = null

    /**
     * Bumped for every new or cancelled attempt. A finished coroutine only publishes
     * its result while it is still the current attempt, so a cancelled sign-in cannot
     * flash "Socket closed" and a stale attempt cannot clobber a live one's status.
     * All bumps and status publications happen under [signInLock], which makes the
     * check-then-publish atomic; the mid-flow read in [signIn] is a volatile read.
     */
    @Volatile
    private var signInAttempt = 0

    private val signInLock = Any()

    private var signInJob: Job? = null

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    /**
     * Sign-in runs here, NOT in the caller's scope.
     *
     * The flow suspends across a trip to an external browser, and Android is free
     * to destroy the Activity while the user is over there. A composition- or
     * Activity-scoped coroutine is cancelled when that happens, so the token
     * exchange never runs and the app silently returns to the sign-in screen
     * with nothing to show for it. This scope lives as long as the process.
     */
    private val signInScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _signIn = MutableStateFlow(initialSignInStatus())
    val signInStatus: StateFlow<SignInStatus> = _signIn.asStateFlow()

    /**
     * A pending-sign-in marker with no tokens means Android killed this process while
     * the user was in the browser: the loopback server died with it, so Google's
     * redirect hit a closed port. Nothing can be resumed — the verifier is gone — but
     * the user deserves the explanation rather than a silently reset screen.
     */
    private fun initialSignInStatus(): SignInStatus {
        if (!flags.getBoolean(KEY_SIGN_IN_PENDING, false)) return SignInStatus.Idle
        flags.edit().putBoolean(KEY_SIGN_IN_PENDING, false).apply()
        if (store.isStored(KEY_REFRESH_TOKEN)) return SignInStatus.Idle
        return SignInStatus.Failed(
            "Android closed Sundial while you were signing in, so the browser had nowhere " +
                "to return to. Try again, and come back to the app soon after approving.",
        )
    }

    /**
     * Starts sign-in, or does nothing if one is already in flight.
     *
     * Runs against the application context, never the caller's Activity: this
     * coroutine deliberately outlives the Activity, and holding a destroyed one
     * would both leak it and fail to start the browser. BrowserLauncher already
     * adds FLAG_ACTIVITY_NEW_TASK when handed a non-Activity context.
     */
    fun beginSignIn() {
        synchronized(signInLock) {
            if (_signIn.value == SignInStatus.Running) return
            // The setup screen is only reachable with a broken session (no tokens, or
            // credentials half-lost from the store). If a stale grant still lingers,
            // drop it now — otherwise saving the credentials flips the root back to
            // the calendar while this flow is still out in the browser, orphaning it.
            if (_state.value.signedIn) forgetTokens()
            val attempt = ++signInAttempt
            _signIn.value = SignInStatus.Running
            flags.edit().putBoolean(KEY_SIGN_IN_PENDING, true).apply()
            signInJob = signInScope.launch {
                val result = signIn(appContext, attempt)
                synchronized(signInLock) {
                    if (attempt == signInAttempt) {
                        flags.edit().putBoolean(KEY_SIGN_IN_PENDING, false).apply()
                        _signIn.value = result.fold(
                            onSuccess = { SignInStatus.Success(it) },
                            onFailure = { SignInStatus.Failed(it.message ?: "Sign-in failed.") },
                        )
                    }
                }
            }
        }
    }

    /** Clears a finished result once the UI has shown it. */
    fun clearSignInStatus() {
        if (_signIn.value != SignInStatus.Running) _signIn.value = SignInStatus.Idle
    }

    fun cancelSignInFlow() {
        synchronized(signInLock) {
            signInAttempt++
            // Actually cancel the coroutine: closing the server only unblocks the
            // redirect wait, and an exchange already in flight would otherwise run
            // to completion and sign the user in after they cancelled.
            signInJob?.cancel()
            signInJob = null
            flags.edit().putBoolean(KEY_SIGN_IN_PENDING, false).apply()
            cancelSignIn()
            _signIn.value = SignInStatus.Idle
        }
    }

    val clientId: String? get() = store.getString(KEY_CLIENT_ID)
    val clientSecret: String? get() = store.getString(KEY_CLIENT_SECRET)

    /**
     * Returns false when the keystore refused the write — the caller must not start
     * a sign-in then, because the flow would run against the previous credentials.
     */
    fun saveClientCredentials(id: String, secret: String): Boolean = try {
        store.putString(KEY_CLIENT_ID, id.trim())
        store.putString(KEY_CLIENT_SECRET, secret.trim())
        _state.value = readState()
        true
    } catch (t: Exception) {
        // Keystore writes can fail transiently; crashing the tap or silently keeping
        // the old credentials would both mislead. Surface it where sign-in errors show.
        _signIn.value = SignInStatus.Failed(
            "Could not store the credentials securely (${t.message}). Try again in a moment.",
        )
        false
    }

    // ---- sign in -------------------------------------------------------

    private suspend fun signIn(context: Context, attempt: Int): Result<String> {
        val id = clientId
        val secret = clientSecret
        if (id.isNullOrBlank() || secret.isNullOrBlank()) {
            return Result.failure(AuthException("Add your Google OAuth client ID and secret first."))
        }

        val verifier = Pkce.createVerifier()
        val stateParam = Pkce.randomState()

        val server = try {
            // The server filters on the state value, so a request from a process that
            // does not know it can never consume the one-shot redirect wait.
            LoopbackServer(expectedState = stateParam)
        } catch (t: Throwable) {
            // On GrapheneOS with the Network toggle off, even the loopback *bind* fails
            // with EACCES — this is the first thing sign-in does, so the friendly DNS
            // message further down is unreachable. Point at the permission here.
            return Result.failure(
                AuthException(
                    "Could not open a local port for the sign-in redirect: ${t.message}. " +
                        "On GrapheneOS this usually means Sundial's Network permission is " +
                        "off — check Settings → Apps → Sundial → Permissions → Network, " +
                        "then try again.",
                ),
            )
        }
        pendingServer = server

        return try {
            // Explicitly on the main thread: sign-in now runs in an application
            // scope on Dispatchers.Default, and starting an Activity (Custom Tab)
            // from a background thread is not something to rely on.
            val opened = withContext(Dispatchers.Main) {
                BrowserLauncher.open(
                    context,
                    authorizationUrl(id, server.redirectUri, verifier, stateParam),
                )
            }
            if (!opened) {
                throw AuthException(
                    "No browser is installed. Sundial signs in through your browser, so one is required.",
                )
            }

            val params = withTimeoutOrNull(SIGN_IN_TIMEOUT_MS) { awaitRedirect(server) }
                ?: throw AuthException("Sign-in timed out.")

            // The server already enforces the state match (it 404s everything else),
            // so this is an invariant assertion rather than the validation itself.
            if (params["state"] != stateParam) {
                throw AuthException("The sign-in response did not match this request.")
            }
            val code = params["code"] ?: throw AuthException(
                params["error_description"] ?: params["error"] ?: "Sign-in was cancelled.",
            )

            val token = postForm(
                mapOf(
                    "code" to code,
                    "client_id" to id,
                    "client_secret" to secret,
                    "redirect_uri" to server.redirectUri,
                    "grant_type" to "authorization_code",
                    "code_verifier" to verifier,
                ),
            )

            val refreshToken = token.refreshToken
                ?: throw AuthException(
                    "Google did not return a refresh token. Revoke Sundial's access at " +
                        "myaccount.google.com/permissions and sign in again.",
                )

            // Google's granular-consent screen lets the account be approved with the
            // calendar checkbox unticked; the exchange still succeeds. Storing that
            // grant would show a signed-in app where every calendar call 403s forever.
            val granted = token.scope?.split(' ')?.filter { it.isNotBlank() }
            if (granted != null && AuthConfig.CALENDAR_SCOPE !in granted) {
                revokeToken(refreshToken)
                throw AuthException(
                    "Google approved the account but not calendar access. Sign in again " +
                        "and tick the checkbox that mentions your calendars on the " +
                        "consent screen.",
                )
            }

            val email = emailFromIdToken(token.idToken)

            // A cancelled attempt must not complete the login behind the user's back.
            // Job cancellation stops the exchange at every suspension point; this
            // closes the last window, where the exchange finished first. The commit is
            // straight-line keystore writes that cancel() cannot interrupt, so the
            // re-check and the writes sit under the same lock cancelSignInFlow's bump
            // takes — a Cancel either lands before this block (nothing is stored) or
            // after it (the sign-in had already completed).
            val committed = synchronized(signInLock) {
                if (attempt == signInAttempt) {
                    store.putString(KEY_REFRESH_TOKEN, refreshToken)
                    store.putString(KEY_ACCESS_TOKEN, token.accessToken)
                    store.putLong(KEY_ACCESS_EXPIRY, System.currentTimeMillis() + token.expiresIn * 1000L)
                    store.putString(KEY_EMAIL, email)
                    _state.value = readState()
                    true
                } else {
                    false
                }
            }
            if (!committed) {
                // The unwanted grant is revoked (off the lock — it's a network call).
                revokeToken(refreshToken)
                throw AuthException("Sign-in was cancelled.")
            }

            Result.success(email ?: "your Google account")
        } catch (t: Throwable) {
            // Cancellation is not a failure to report — and swallowing it would let a
            // cancelled coroutine keep running to the publish step.
            if (t is CancellationException) throw t
            Result.failure(if (t is AuthException) t else AuthException(describeSignInError(t)))
        } finally {
            server.close()
            // A stale attempt unwinding late must not clobber the reference to a
            // newer attempt's live server — that would disarm its Cancel button.
            if (pendingServer === server) pendingServer = null
        }
    }

    /**
     * State the actual condition, not the resolver's internals.
     *
     * On GrapheneOS a denied Network permission does not refuse connections --
     * it hides the DNS resolver, so the app sees UnknownHostException
     * ("Unable to resolve host ...") even with Wi-Fi visibly connected. Every
     * GrapheneOS user who unticks Network at install hits this on sign-in.
     */
    private fun describeSignInError(t: Throwable): String = when (t) {
        is java.net.UnknownHostException ->
            "Could not reach Google — this app has no network access. If Wi-Fi is on, " +
                "check Settings → Apps → Sundial → Permissions → Network (GrapheneOS " +
                "disables DNS entirely when that toggle is off), then try again."
        is java.net.SocketTimeoutException -> "Google took too long to answer. Try again."
        is javax.net.ssl.SSLException ->
            "Secure connection to Google failed. Check the clock and any VPN, then try again."
        else -> t.message ?: "Sign-in failed."
    }

    /** Unblocks a sign-in that is waiting on the browser. */
    fun cancelSignIn() {
        pendingServer?.close()
        pendingServer = null
    }

    private suspend fun awaitRedirect(server: LoopbackServer): Map<String, String> =
        suspendCancellableCoroutine { continuation ->
            val thread = Thread({
                try {
                    val result = server.awaitRedirect()
                    if (continuation.isActive) continuation.resume(result)
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(t)
                }
            }, "sundial-oauth-redirect")
            continuation.invokeOnCancellation { server.close() }
            thread.isDaemon = true
            thread.start()
        }

    private fun authorizationUrl(
        clientId: String,
        redirectUri: String,
        verifier: String,
        state: String,
    ): String = Uri.parse(AuthConfig.AUTH_ENDPOINT).buildUpon()
        .appendQueryParameter("client_id", clientId)
        .appendQueryParameter("redirect_uri", redirectUri)
        .appendQueryParameter("response_type", "code")
        .appendQueryParameter("scope", AuthConfig.SCOPE_PARAM)
        .appendQueryParameter("code_challenge", Pkce.challengeFor(verifier))
        .appendQueryParameter("code_challenge_method", "S256")
        .appendQueryParameter("state", state)
        .appendQueryParameter("access_type", "offline")
        .appendQueryParameter("prompt", "consent")
        .build()
        .toString()

    // ---- tokens --------------------------------------------------------

    /** Returns a valid access token, refreshing it when needed. */
    suspend fun accessToken(): String = refreshMutex.withLock {
        val cached = store.getString(KEY_ACCESS_TOKEN)
        val expiry = store.getLong(KEY_ACCESS_EXPIRY)
        if (cached != null && expiry - System.currentTimeMillis() > TOKEN_SKEW_MS) {
            return@withLock cached
        }

        // A null read means one of two very different things, and only one of them may
        // destroy state: genuinely absent (reset auth so the setup screen returns) vs
        // stored-but-keystore-busy (fail soft and retry later — deleting here would
        // turn a transient keymaster hiccup after boot/OTA into a permanent sign-out).
        val refreshToken = store.getString(KEY_REFRESH_TOKEN)
            ?: throw absentOrUnavailable(KEY_REFRESH_TOKEN, "Not signed in.")
        val id = clientId ?: throw absentOrUnavailable(KEY_CLIENT_ID, "Missing client ID.")
        val secret = clientSecret
            ?: throw absentOrUnavailable(KEY_CLIENT_SECRET, "Missing client secret.")

        val token = try {
            postForm(
                mapOf(
                    "client_id" to id,
                    "client_secret" to secret,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token",
                ),
            )
        } catch (e: AuthException) {
            if (e.requiresReauth) forgetTokens()
            throw e
        }

        val accessToken = token.accessToken
            ?: throw AuthException("Google returned no access token.", requiresReauth = true)

        store.putString(KEY_ACCESS_TOKEN, accessToken)
        store.putLong(KEY_ACCESS_EXPIRY, System.currentTimeMillis() + token.expiresIn * 1000L)
        token.refreshToken?.let { store.putString(KEY_REFRESH_TOKEN, it) }
        accessToken
    }

    /** Drops the cached access token so the next call forces a refresh. */
    fun invalidateAccessToken() {
        store.putString(KEY_ACCESS_TOKEN, null)
        store.putLong(KEY_ACCESS_EXPIRY, 0L)
    }

    suspend fun signOut(revoke: Boolean = true) {
        val refreshToken = store.getString(KEY_REFRESH_TOKEN)
        // Local flip first: the UI must not sit on a signed-in-looking calendar while
        // the network revocation waits out its timeouts on a bad connection.
        forgetTokens()
        if (revoke && refreshToken != null) revokeToken(refreshToken)
    }

    /**
     * Called when Google definitively rejects the session at the API layer (a 401
     * that survives a forced refresh). Without this the app stays "signed in" with
     * every sync failing and no path back to the setup screen.
     */
    fun sessionRejected() {
        forgetTokens()
    }

    /** Best-effort revocation at Google's end; failure changes nothing locally. */
    private suspend fun revokeToken(token: String) {
        // NonCancellable: this mostly runs as cleanup on an already-cancelled attempt,
        // where a cancellable withContext would throw before doing anything.
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(AuthConfig.REVOKE_ENDPOINT)
                    .post(FormBody.Builder().add("token", token).build())
                    .build()
                Http.client.newCall(request).execute().close()
            }
        }
    }

    private fun forgetTokens() {
        store.clear(KEY_REFRESH_TOKEN, KEY_ACCESS_TOKEN, KEY_ACCESS_EXPIRY, KEY_EMAIL)
        _state.value = readState()
    }

    /** The stored grant is unusable; drop the remnants so the UI returns to setup. */
    private fun missingAuth(message: String): AuthException {
        forgetTokens()
        return AuthException(message, requiresReauth = true)
    }

    /**
     * "Absent" resets auth state; "stored but unreadable right now" must not — the
     * ciphertext is intact and the keystore will answer again shortly.
     */
    private fun absentOrUnavailable(key: String, missingMessage: String): AuthException =
        if (store.isStored(key)) {
            AuthException("Secure storage is temporarily unavailable — try again in a moment.")
        } else {
            missingAuth(missingMessage)
        }

    // ---- helpers -------------------------------------------------------

    private suspend fun postForm(params: Map<String, String>): TokenResponse =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply {
                params.forEach { (key, value) -> add(key, value) }
            }.build()
            val request = Request.Builder().url(AuthConfig.TOKEN_ENDPOINT).post(body).build()

            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = runCatching { Http.json.decodeFromString<TokenResponse>(text) }
                val body = parsed.getOrNull()
                if (!response.isSuccessful || body?.accessToken == null) {
                    // A 2xx whose body will not parse is not "HTTP 200" — say what
                    // actually went wrong, or diagnosing it is guesswork.
                    val reason = body?.errorDescription
                        ?: body?.error
                        ?: if (response.isSuccessful) {
                            "Unexpected reply from Google — the token response could not " +
                                "be read" +
                                (parsed.exceptionOrNull()?.message?.let { " ($it)" } ?: ".")
                        } else {
                            "HTTP ${response.code}"
                        }
                    // Only a parsed OAuth error may condemn the stored grant. This
                    // endpoint is HTTPS against Google's own certificate, so any
                    // response that reaches here is genuinely Google's — but a bare
                    // status code says nothing about whether the grant is dead.
                    throw AuthException(
                        reason,
                        requiresReauth = body?.error == "invalid_grant" ||
                            body?.error == "invalid_client",
                    )
                }
                body
            }
        }

    private fun emailFromIdToken(idToken: String?): String? {
        val payload = idToken?.split('.')?.getOrNull(1) ?: return null
        return runCatching {
            val decoded = String(
                Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
            Http.json.parseToJsonElement(decoded).jsonObject["email"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    private fun readState() = AuthState(
        // isStored, not getString: a keystore that is busy right after boot or an OTA
        // must not present a signed-in user with an empty setup screen. A value that
        // turns out permanently unreadable is deleted on its first real use, so this
        // resolves itself for genuinely dead ciphertext.
        hasCredentials = store.isStored(KEY_CLIENT_ID) && store.isStored(KEY_CLIENT_SECRET),
        signedIn = store.isStored(KEY_REFRESH_TOKEN),
        email = store.getString(KEY_EMAIL),
    )

    private companion object {
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ACCESS_EXPIRY = "access_expiry"
        const val KEY_EMAIL = "account_email"

        const val KEY_SIGN_IN_PENDING = "sign_in_pending"

        const val TOKEN_SKEW_MS = 60_000L
        const val SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
