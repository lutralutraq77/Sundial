package dev.danny.sundial.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.danny.sundial.net.Http
import dev.danny.sundial.net.NetworkDiagnostics
import dev.danny.sundial.net.NetworkFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    @Volatile
    private var pendingServer: LoopbackServer? = null

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

    private val _signIn = MutableStateFlow<SignInStatus>(SignInStatus.Idle)
    val signInStatus: StateFlow<SignInStatus> = _signIn.asStateFlow()

    /**
     * Starts sign-in, or does nothing if one is already in flight.
     *
     * Runs against the application context, never the caller's Activity: this
     * coroutine deliberately outlives the Activity, and holding a destroyed one
     * would both leak it and fail to start the browser. BrowserLauncher already
     * adds FLAG_ACTIVITY_NEW_TASK when handed a non-Activity context.
     */
    fun beginSignIn() {
        if (_signIn.value == SignInStatus.Running) return
        _signIn.value = SignInStatus.Running
        signInScope.launch {
            val result = signIn(appContext)
            _signIn.value = result.fold(
                onSuccess = { SignInStatus.Success(it) },
                onFailure = { SignInStatus.Failed(it.message ?: "Sign-in failed.") },
            )
        }
    }

    /** Clears a finished result once the UI has shown it. */
    fun clearSignInStatus() {
        if (_signIn.value != SignInStatus.Running) _signIn.value = SignInStatus.Idle
    }

    fun cancelSignInFlow() {
        cancelSignIn()
        _signIn.value = SignInStatus.Idle
    }

    val clientId: String? get() = store.getString(KEY_CLIENT_ID)
    val clientSecret: String? get() = store.getString(KEY_CLIENT_SECRET)

    fun saveClientCredentials(id: String, secret: String) {
        store.putString(KEY_CLIENT_ID, id.trim())
        store.putString(KEY_CLIENT_SECRET, secret.trim())
        _state.value = readState()
    }

    // ---- sign in -------------------------------------------------------

    suspend fun signIn(context: Context): Result<String> {
        val id = clientId
        val secret = clientSecret
        if (id.isNullOrBlank() || secret.isNullOrBlank()) {
            return Result.failure(AuthException("Add your Google OAuth client ID and secret first."))
        }

        val server = try {
            LoopbackServer()
        } catch (t: Throwable) {
            return Result.failure(AuthException("Could not open a local port for the sign-in redirect: ${t.message}"))
        }
        pendingServer = server

        return try {
            val verifier = Pkce.createVerifier()
            val stateParam = Pkce.randomState()
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

            if (params["state"] != stateParam) {
                throw AuthException("The sign-in response did not match this request.")
            }
            val code = params["code"] ?: throw AuthException(
                params["error_description"] ?: params["error"] ?: "Sign-in was cancelled.",
            )

            // The redirect arrives while the browser is still in front: awaitRedirect
            // returns the instant Google's GET hits the loopback port, long before the
            // user has dismissed the "Google approved Sundial" page. Exchanging the code
            // right here means issuing an HTTPS request from a process with no visible
            // component, which Android 15 refuses -- and reports as UnknownHostException,
            // so it reads as broken DNS rather than as the deliberate block it is.
            //
            // Moving sign-in into an application scope is what made this reliable rather
            // than intermittent: the exchange used to be cancelled along with the
            // Activity, so it never ran at all. Now it always runs, always from the
            // background. Wait for a visible window first.
            awaitForeground()

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

            val email = emailFromIdToken(token.idToken)
            store.putString(KEY_REFRESH_TOKEN, refreshToken)
            store.putString(KEY_ACCESS_TOKEN, token.accessToken)
            store.putLong(KEY_ACCESS_EXPIRY, System.currentTimeMillis() + token.expiresIn * 1000L)
            store.putString(KEY_EMAIL, email)
            _state.value = readState()

            Result.success(email ?: "your Google account")
        } catch (t: Throwable) {
            Log.w(TAG, "Sign-in failed", t)
            Result.failure(
                if (t is AuthException) t else AuthException(describeSignInError(t), cause = t),
            )
        } finally {
            server.close()
            pendingServer = null
        }
    }

    /**
     * Report what was observed, then what the system says about it.
     *
     * The wording this replaces named the GrapheneOS Network toggle for every
     * UnknownHostException. That is the one cause it could not be: LoopbackServer binds
     * 127.0.0.1 earlier in this same method, and GrapheneOS guards loopback with the
     * same permission, so a revoked toggle never reaches the token exchange. See
     * NetworkFailure for the full argument.
     */
    private suspend fun describeSignInError(t: Throwable): String {
        val diagnostics = runCatching {
            NetworkDiagnostics.capture(appContext, appWasForeground = isForeground())
        }.getOrNull()
        return listOfNotNull(NetworkFailure.describe(t), diagnostics?.explain())
            .joinToString(" ")
    }

    /**
     * Suspends until Sundial has a visible window again, giving up after
     * [FOREGROUND_WAIT_MS] and letting the request proceed regardless.
     *
     * The timeout matters: if the user walks away from the browser, failing with a
     * blunt "you were in the background" is worse than trying the exchange and
     * reporting whatever actually comes back.
     */
    private suspend fun awaitForeground() {
        val lifecycle = withContext(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        withTimeoutOrNull(FOREGROUND_WAIT_MS) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
        }
    }

    private suspend fun isForeground(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
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

        val refreshToken = store.getString(KEY_REFRESH_TOKEN)
            ?: throw AuthException("Not signed in.", requiresReauth = true)
        val id = clientId ?: throw AuthException("Missing client ID.", requiresReauth = true)
        val secret = clientSecret ?: throw AuthException("Missing client secret.", requiresReauth = true)

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
        if (revoke && refreshToken != null) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val request = Request.Builder()
                        .url(AuthConfig.REVOKE_ENDPOINT)
                        .post(FormBody.Builder().add("token", refreshToken).build())
                        .build()
                    Http.client.newCall(request).execute().close()
                }
            }
        }
        forgetTokens()
    }

    private fun forgetTokens() {
        store.clear(KEY_REFRESH_TOKEN, KEY_ACCESS_TOKEN, KEY_ACCESS_EXPIRY, KEY_EMAIL)
        _state.value = readState()
    }

    // ---- helpers -------------------------------------------------------

    private suspend fun postForm(params: Map<String, String>): TokenResponse =
        retryingOnTransientNetwork { postFormOnce(params) }

    /**
     * Retries only the failures that prove nothing left the device.
     *
     * OkHttp will not do this: retryOnConnectionFailure does not cover a first-attempt
     * UnknownHostException, so a single unlucky lookup ends the whole sign-in. Retrying
     * an authorization-code exchange is safe precisely because these two exceptions mean
     * no request was ever sent -- a code that Google has actually seen would come back
     * as invalid_grant instead, which is an AuthException and propagates immediately.
     */
    private suspend fun <T> retryingOnTransientNetwork(block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(NETWORK_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                if (t !is java.net.UnknownHostException && t !is java.net.ConnectException) throw t
                last = t
                if (attempt < NETWORK_ATTEMPTS - 1) delay(RETRY_BASE_MS shl attempt)
            }
        }
        throw last ?: IllegalStateException("Retry loop ended without a result")
    }

    private suspend fun postFormOnce(params: Map<String, String>): TokenResponse =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply {
                params.forEach { (key, value) -> add(key, value) }
            }.build()
            val request = Request.Builder().url(AuthConfig.TOKEN_ENDPOINT).post(body).build()

            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val parsed = runCatching { Http.json.decodeFromString<TokenResponse>(text) }.getOrNull()
                if (!response.isSuccessful || parsed?.accessToken == null) {
                    val reason = parsed?.errorDescription
                        ?: parsed?.error
                        ?: "HTTP ${response.code}"
                    throw AuthException(
                        reason,
                        requiresReauth = parsed?.error == "invalid_grant" ||
                            parsed?.error == "invalid_client" ||
                            response.code == 400 || response.code == 401,
                    )
                }
                parsed
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
        hasCredentials = !store.getString(KEY_CLIENT_ID).isNullOrBlank() &&
            !store.getString(KEY_CLIENT_SECRET).isNullOrBlank(),
        signedIn = !store.getString(KEY_REFRESH_TOKEN).isNullOrBlank(),
        email = store.getString(KEY_EMAIL),
    )

    private companion object {
        const val TAG = "SundialAuth"

        const val KEY_CLIENT_ID = "client_id"
        const val KEY_CLIENT_SECRET = "client_secret"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ACCESS_EXPIRY = "access_expiry"
        const val KEY_EMAIL = "account_email"

        const val TOKEN_SKEW_MS = 60_000L
        const val SIGN_IN_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * How long to wait for a visible window before exchanging the code anyway.
         * The loopback page returns automatically, so in practice this resolves in
         * well under a second; the budget is for the user who wanders off.
         */
        const val FOREGROUND_WAIT_MS = 60_000L

        const val NETWORK_ATTEMPTS = 3
        const val RETRY_BASE_MS = 500L
    }
}
