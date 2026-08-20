package dev.danny.sundial.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

/**
 * [requiresReauth] means the stored grant is gone for good — the UI should send the
 * user back through sign-in rather than offering a retry.
 */
class AuthException(
    message: String,
    val requiresReauth: Boolean = false,
) : Exception(message)

data class AuthState(
    val hasCredentials: Boolean = false,
    val signedIn: Boolean = false,
    val email: String? = null,
)

/**
 * Progress of a sign-in, held by the repository rather than by the screen so it
 * survives the Activity being destroyed while the user is in the browser.
 */
sealed interface SignInStatus {
    data object Idle : SignInStatus
    data object Running : SignInStatus
    data class Success(val email: String) : SignInStatus
    data class Failed(val message: String) : SignInStatus
}

object AuthConfig {
    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"

    /** The one scope the app cannot function without; granted scopes are checked against it. */
    const val CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar"

    val SCOPES = listOf(
        "openid",
        "email",
        CALENDAR_SCOPE,
    )

    val SCOPE_PARAM: String get() = SCOPES.joinToString(" ")
}
