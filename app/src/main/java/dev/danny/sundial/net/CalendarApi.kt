package dev.danny.sundial.net

import android.net.Uri
import dev.danny.sundial.auth.AuthException
import dev.danny.sundial.auth.AuthRepository
import dev.danny.sundial.net.dto.ApiErrorEnvelope
import dev.danny.sundial.net.dto.ApiEvent
import dev.danny.sundial.net.dto.CalendarListEntry
import dev.danny.sundial.net.dto.CalendarListResponse
import dev.danny.sundial.net.dto.ColorsResponse
import dev.danny.sundial.net.dto.EventsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(
    message: String,
    val code: Int,
    val reason: String? = null,
) : Exception(message) {
    val isNotFound: Boolean get() = code == 404 || code == 410
    val isRateLimited: Boolean
        get() = code == 429 || reason == "rateLimitExceeded" || reason == "userRateLimitExceeded"
    val isForbidden: Boolean get() = code == 403
}

/**
 * Thin client over the Google Calendar REST API v3 — plain HTTPS plus a bearer token,
 * so nothing here needs Google Play Services.
 */
class CalendarApi(private val auth: AuthRepository) {

    // ---- reads ---------------------------------------------------------

    suspend fun calendarList(): List<CalendarListEntry> {
        val all = mutableListOf<CalendarListEntry>()
        var pageToken: String? = null
        do {
            val url = url("users/me/calendarList") {
                appendQueryParameter("maxResults", "250")
                appendQueryParameter("showHidden", "true")
                appendQueryParameter("showDeleted", "false")
                pageToken?.let { appendQueryParameter("pageToken", it) }
            }
            val page = Http.json.decodeFromString<CalendarListResponse>(getBody(url))
            all += page.items
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return all.filterNot { it.deleted }
    }

    suspend fun colors(): ColorsResponse =
        Http.json.decodeFromString(getBody(url("colors") {}))

    /**
     * One page of expanded event instances. Recurrence expansion is done by Google via
     * singleEvents, which is why this app carries no RRULE engine.
     */
    suspend fun events(
        calendarId: String,
        timeMinRfc3339: String,
        timeMaxRfc3339: String,
        pageToken: String? = null,
    ): EventsResponse {
        val url = url("calendars/${enc(calendarId)}/events") {
            appendQueryParameter("singleEvents", "true")
            appendQueryParameter("showDeleted", "false")
            appendQueryParameter("maxResults", "2500")
            appendQueryParameter("timeMin", timeMinRfc3339)
            appendQueryParameter("timeMax", timeMaxRfc3339)
            pageToken?.let { appendQueryParameter("pageToken", it) }
        }
        return Http.json.decodeFromString(getBody(url))
    }

    suspend fun searchEvents(
        calendarId: String,
        query: String,
        timeMinRfc3339: String,
        timeMaxRfc3339: String,
    ): List<ApiEvent> {
        val url = url("calendars/${enc(calendarId)}/events") {
            appendQueryParameter("q", query)
            appendQueryParameter("singleEvents", "true")
            appendQueryParameter("showDeleted", "false")
            appendQueryParameter("maxResults", "250")
            appendQueryParameter("orderBy", "startTime")
            appendQueryParameter("timeMin", timeMinRfc3339)
            appendQueryParameter("timeMax", timeMaxRfc3339)
        }
        return Http.json.decodeFromString<EventsResponse>(getBody(url)).items
    }

    suspend fun event(calendarId: String, eventId: String): ApiEvent =
        Http.json.decodeFromString(getBody(url("calendars/${enc(calendarId)}/events/${enc(eventId)}") {}))

    // ---- writes --------------------------------------------------------

    suspend fun insertEvent(
        calendarId: String,
        body: JsonObject,
        notifyGuests: Boolean,
    ): ApiEvent {
        val url = url("calendars/${enc(calendarId)}/events") {
            appendQueryParameter("sendUpdates", if (notifyGuests) "all" else "none")
        }
        return Http.json.decodeFromString(sendBody("POST", url, body))
    }

    /** events.import preserves an ICS UID, so re-importing the same file updates rather than duplicates. */
    suspend fun importEvent(calendarId: String, body: JsonObject): ApiEvent {
        val url = url("calendars/${enc(calendarId)}/events/import") {}
        return Http.json.decodeFromString(sendBody("POST", url, body))
    }

    suspend fun patchEvent(
        calendarId: String,
        eventId: String,
        body: JsonObject,
        notifyGuests: Boolean,
    ): ApiEvent {
        val url = url("calendars/${enc(calendarId)}/events/${enc(eventId)}") {
            appendQueryParameter("sendUpdates", if (notifyGuests) "all" else "none")
        }
        return Http.json.decodeFromString(sendBody("PATCH", url, body))
    }

    suspend fun deleteEvent(calendarId: String, eventId: String, notifyGuests: Boolean) {
        val url = url("calendars/${enc(calendarId)}/events/${enc(eventId)}") {
            appendQueryParameter("sendUpdates", if (notifyGuests) "all" else "none")
        }
        // 410 Gone means it is already deleted, which is the state we were after.
        request(Request.Builder().url(url).delete(), tolerate = setOf(404, 410))
    }

    // ---- plumbing ------------------------------------------------------

    private suspend fun getBody(url: String): String =
        request(Request.Builder().url(url).get())

    private suspend fun sendBody(method: String, url: String, body: JsonObject?): String =
        request(
            Request.Builder().url(url).method(method, (body?.toString() ?: "{}").toRequestBody(JSON_MEDIA)),
        )

    /**
     * Runs a request with a bearer token and returns the response body. A 401 triggers
     * one forced token refresh and retry, so a token that expires mid-flight never
     * surfaces to the user.
     */
    private suspend fun request(
        builder: Request.Builder,
        tolerate: Set<Int> = emptySet(),
    ): String = withContext(Dispatchers.IO) {
        // At most two passes: the original request, then one after a forced refresh.
        var attempt = 0
        while (attempt < 2) {
            val token = auth.accessToken()
            val response = Http.client.newCall(builder.header("Authorization", "Bearer $token").build()).execute()
            val payload: String
            val code: Int
            try {
                code = response.code
                payload = response.body?.string().orEmpty()
            } finally {
                response.close()
            }

            if (code == 401 && attempt == 0) {
                attempt++
                auth.invalidateAccessToken()
                continue
            }
            if (code == 401) {
                // A freshly refreshed token was still rejected: the session is dead.
                // Telling the repository is what actually returns the user to the
                // setup screen — nothing downstream acts on requiresReauth alone.
                auth.sessionRejected()
                throw AuthException("Google rejected the access token.", requiresReauth = true)
            }
            if (code in tolerate) return@withContext payload
            if (code !in 200..299) throw errorFor(code, payload)
            return@withContext payload
        }
        auth.sessionRejected()
        throw AuthException("Google rejected the access token.", requiresReauth = true)
    }

    private fun errorFor(code: Int, payload: String): ApiException {
        val parsed = runCatching { Http.json.decodeFromString<ApiErrorEnvelope>(payload) }.getOrNull()?.error
        val reason = parsed?.errors?.firstOrNull()?.reason
        val message = parsed?.message?.takeIf { it.isNotBlank() }
            ?: "Google Calendar returned HTTP $code"
        return ApiException(message, code, reason)
    }

    private fun url(path: String, build: Uri.Builder.() -> Unit): String =
        Uri.parse("$BASE/$path").buildUpon().apply(build).build().toString()

    private fun enc(value: String): String = Uri.encode(value)

    companion object {
        private const val BASE = "https://www.googleapis.com/calendar/v3"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
