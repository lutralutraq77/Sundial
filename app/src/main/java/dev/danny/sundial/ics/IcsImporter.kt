package dev.danny.sundial.ics

import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.data.CalendarRepository
import dev.danny.sundial.net.ApiException
import dev.danny.sundial.net.CalendarApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ImportPreview(
    val events: List<IcsEvent>,
    val calendarName: String?,
    val warnings: List<String>,
) {
    val importable: List<IcsEvent> get() = events.filterNot { it.isOverride }
    val overrideCount: Int get() = events.count { it.isOverride }
}

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val messages: List<String>,
)

/**
 * Turns parsed VEVENTs into Google Calendar events.
 *
 * Events carrying a UID go through events.import, which keeps the UID — so importing
 * the same file twice updates the existing events instead of duplicating them.
 */
class IcsImporter(
    private val api: CalendarApi,
    private val repository: CalendarRepository,
) {

    fun preview(text: String): ImportPreview {
        val parsed = IcsParser.parse(text)
        return ImportPreview(parsed.events, parsed.calendarName, parsed.warnings)
    }

    suspend fun import(
        events: List<IcsEvent>,
        calendarId: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var failed = 0
        var skipped = 0
        val messages = mutableListOf<String>()

        val candidates = events.filter { it.hasUsableTimes }
        val overrides = events.size - candidates.size
        if (overrides > 0) {
            skipped += overrides
            messages += "$overrides event(s) had no usable start time."
        }

        candidates.forEachIndexed { index, event ->
            if (event.isOverride) {
                // A single modified occurrence of a series; Google's import endpoint has
                // no way to attach it, so say so rather than silently dropping it.
                skipped++
                messages += "Skipped a modified occurrence of \"${event.summary ?: event.uid ?: "an event"}\"."
            } else if (event.status == "cancelled") {
                skipped++
            } else {
                try {
                    val body = event.toJson(includeUid = event.uid != null)
                    if (event.uid != null) {
                        try {
                            api.importEvent(calendarId, body)
                        } catch (_: ApiException) {
                            // Some exports carry UIDs Google will not accept; fall back
                            // to a plain insert so the event still lands.
                            api.insertEvent(calendarId, event.toJson(includeUid = false), notifyGuests = false)
                        }
                    } else {
                        api.insertEvent(calendarId, body, notifyGuests = false)
                    }
                    imported++
                } catch (e: Exception) {
                    failed++
                    if (messages.size < 8) {
                        messages += "\"${event.summary ?: "Untitled"}\" failed: ${e.message}"
                    }
                }
            }
            onProgress(index + 1, candidates.size)
        }

        repository.notifyChanged()
        ImportResult(imported, skipped, failed, messages)
    }
}

/** Maps a parsed VEVENT onto the Google Calendar event resource. */
fun IcsEvent.toJson(includeUid: Boolean): JsonObject = buildJsonObject {
    put("summary", summary?.takeIf { it.isNotBlank() } ?: "(No title)")
    description?.let { put("description", it) }
    location?.let { put("location", it) }
    if (includeUid && uid != null) put("iCalUID", uid)

    if (allDay) {
        putJsonObject("start") { put("date", startDate) }
        putJsonObject("end") { put("date", endDateExclusive ?: startDate) }
    } else {
        val zone = TimeUtil.safeZone(timeZone)
        putJsonObject("start") {
            put("dateTime", TimeUtil.toRfc3339(startMillis ?: 0L, zone))
            put("timeZone", zone.id)
        }
        putJsonObject("end") {
            put("dateTime", TimeUtil.toRfc3339(endMillis ?: ((startMillis ?: 0L) + 3_600_000L), zone))
            put("timeZone", zone.id)
        }
    }

    if (recurrence.isNotEmpty()) {
        putJsonArray("recurrence") { recurrence.forEach { add(it) } }
    }

    putJsonObject("reminders") {
        if (reminderMinutes.isEmpty()) {
            put("useDefault", true)
        } else {
            put("useDefault", false)
            putJsonArray("overrides") {
                reminderMinutes.take(5).forEach { minutes ->
                    addJsonObject {
                        put("method", "popup")
                        put("minutes", minutes)
                    }
                }
            }
        }
    }

    if (transparent) put("transparency", "transparent")
    if (status == "tentative" || status == "confirmed") put("status", status)
}
