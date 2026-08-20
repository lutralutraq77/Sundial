package dev.danny.sundial.data

import dev.danny.sundial.core.CalendarInfo
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.RecurrenceScope
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.net.CalendarApi
import dev.danny.sundial.net.dto.ApiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.temporal.ChronoUnit

/**
 * Reads come from the local cache so the UI works offline; writes go straight to Google
 * and the returned event is folded back into the cache so the change is visible before
 * the next sync. Writes are never queued offline — a failure is reported, not hidden.
 */
class CalendarRepository(
    private val api: CalendarApi,
    private val store: LocalStore,
    private val prefs: AppPrefs,
) {

    private val _changes = MutableStateFlow(0L)

    /** Bumps whenever cached data changes, so screens can re-query. */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    fun notifyChanged() {
        _changes.value = System.currentTimeMillis()
    }

    // ---- reads ---------------------------------------------------------

    suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) { store.calendars() }

    suspend fun writableCalendars(): List<CalendarInfo> =
        withContext(Dispatchers.IO) { store.calendars().filter { it.canWrite } }

    suspend fun eventsBetween(startMillis: Long, endMillis: Long): List<EventItem> =
        withContext(Dispatchers.IO) {
            val events = store.eventsBetween(startMillis, endMillis)
            if (prefs.showDeclined) events else events.filterNot { it.isDeclinedBySelf() }
        }

    suspend fun event(calendarId: String, eventId: String): EventItem? =
        withContext(Dispatchers.IO) { store.event(calendarId, eventId) }

    suspend fun search(query: String): List<EventItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else store.search(query)
    }

    suspend fun eventCount(): Int = withContext(Dispatchers.IO) { store.eventCount() }

    suspend fun setCalendarVisible(calendarId: String, visible: Boolean) {
        withContext(Dispatchers.IO) { store.setCalendarVisible(calendarId, visible) }
        notifyChanged()
    }

    private fun eventColors(): Map<String, String> =
        store.meta(SyncEngine.META_EVENT_COLORS)
            ?.split(';')
            ?.mapNotNull { entry ->
                val key = entry.substringBefore('=', "")
                val value = entry.substringAfter('=', "")
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            ?.toMap()
            ?: emptyMap()

    // ---- writes --------------------------------------------------------

    suspend fun createEvent(draft: EventDraft): Result<EventItem> = withContext(Dispatchers.IO) {
        draft.validationError()?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
        runCatching {
            val created = api.insertEvent(
                calendarId = draft.calendarId,
                body = draft.toJson(includeRecurrence = true),
                notifyGuests = prefs.notifyGuests && draft.attendeeEmails.isNotEmpty(),
            )
            cacheWrittenEvent(draft.calendarId, created)
        }
    }

    /**
     * [scope] decides whether a recurring event is edited as one instance or as the whole
     * series. Editing the series patches the master event, whose expansion only reaches
     * the cache on the next sync, so the caller should sync afterwards.
     */
    suspend fun updateEvent(
        draft: EventDraft,
        scope: RecurrenceScope,
        original: EventItem? = null,
    ): Result<EventItem?> = withContext(Dispatchers.IO) {
        val eventId = draft.eventId
            ?: return@withContext Result.failure(IllegalStateException("This event has no id."))
        draft.validationError()?.let { return@withContext Result.failure(IllegalArgumentException(it)) }

        runCatching {
            val seriesId = draft.recurringEventId
            val editingSeries = scope == RecurrenceScope.ALL && seriesId != null
            val notify = prefs.notifyGuests && draft.attendeeEmails.isNotEmpty()

            if (editingSeries) {
                // The master event is the only place the repeat rule lives, and its start
                // is the *first* occurrence — patching it with this instance's times would
                // shift the whole series. Fetch it and apply the user's delta instead.
                val master = api.event(draft.calendarId, seriesId!!)
                // seriesPatch can only re-anchor timing within the master's own
                // representation; a timed<->all-day flip has no per-occurrence
                // equivalent and used to be dropped silently.
                val masterAllDay = master.start?.date != null
                if (draft.allDay != masterAllDay) {
                    throw IllegalArgumentException(
                        "Switching between all-day and timed can't be applied to the " +
                            "whole series. Save it for this event only.",
                    )
                }
                val body = seriesPatch(draft, original, master)
                api.patchEvent(draft.calendarId, seriesId, body, notify)

                // The response is the master, not a placeable instance; drop the stale
                // series locally and let the next sync repopulate the expansion.
                store.deleteRecurringSeries(draft.calendarId, seriesId)
                notifyChanged()
                null
            } else {
                val updated = api.patchEvent(
                    calendarId = draft.calendarId,
                    eventId = eventId,
                    // Only a standalone event may gain or lose a repeat rule here; an
                    // instance has no recurrence field of its own to send.
                    body = draft.toJson(includeRecurrence = seriesId == null),
                    notifyGuests = notify,
                )
                cacheWrittenEvent(draft.calendarId, updated)
            }
        }
    }

    /**
     * Builds a patch for a whole series: every edited field except the timing, plus the
     * master's own start/end shifted by however much the user moved this occurrence.
     */
    private fun seriesPatch(draft: EventDraft, original: EventItem?, master: ApiEvent): JsonObject {
        val fields = draft.toJson(includeRecurrence = false).toMutableMap()
        fields.remove("start")
        fields.remove("end")
        if (original == null) return JsonObject(fields)

        val masterStartDate = TimeUtil.parseDateOrNull(master.start?.date)
        val masterStartMillis = TimeUtil.parseTimestampOrNull(master.start?.dateTime)

        if (draft.allDay && masterStartDate != null) {
            val dayShift = ChronoUnit.DAYS.between(original.firstDay(), draft.start.toLocalDate())
            val lengthDays = ChronoUnit.DAYS.between(draft.start.toLocalDate(), draft.end.toLocalDate()) + 1
            val newStart = masterStartDate.plusDays(dayShift)
            fields["start"] = buildJsonObject { put("date", TimeUtil.toDateString(newStart)) }
            fields["end"] = buildJsonObject {
                put("date", TimeUtil.toDateString(newStart.plusDays(maxOf(1L, lengthDays))))
            }
        } else if (!draft.allDay && masterStartMillis != null) {
            val zone = TimeUtil.safeZone(draft.zoneId)
            val draftStart = draft.start.atZone(zone).toInstant().toEpochMilli()
            val draftEnd = draft.end.atZone(zone).toInstant().toEpochMilli()
            val newStart = masterStartMillis + (draftStart - original.startMillis)
            val newEnd = newStart + (draftEnd - draftStart)
            fields["start"] = buildJsonObject {
                put("dateTime", TimeUtil.toRfc3339(newStart, zone))
                put("timeZone", zone.id)
            }
            fields["end"] = buildJsonObject {
                put("dateTime", TimeUtil.toRfc3339(newEnd, zone))
                put("timeZone", zone.id)
            }
        }
        return JsonObject(fields)
    }

    suspend fun deleteEvent(event: EventItem, scope: RecurrenceScope): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val deletingSeries = scope == RecurrenceScope.ALL && event.recurringEventId != null
                val targetId = if (deletingSeries) event.recurringEventId!! else event.id
                api.deleteEvent(
                    calendarId = event.calendarId,
                    eventId = targetId,
                    notifyGuests = prefs.notifyGuests && event.attendees.isNotEmpty(),
                )
                if (deletingSeries) {
                    store.deleteRecurringSeries(event.calendarId, targetId)
                } else {
                    store.deleteEvent(event.calendarId, event.id)
                }
                notifyChanged()
            }
        }

    /** Sets the signed-in user's RSVP on an event that has a guest list. */
    suspend fun respond(event: EventItem, response: String): Result<EventItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                // events.patch replaces the attendees array wholesale — and Google
                // cannot store an attendee without an address, so an event carrying
                // one cannot be written back without silently removing that guest
                // for everyone. Refuse rather than mutate the guest list.
                if (event.attendees.any { it.email == null }) {
                    throw IllegalStateException(
                        "This event has a guest without an email address; responding " +
                            "from Sundial would remove them. Respond from Google " +
                            "Calendar instead.",
                    )
                }
                // Every field the cache holds must be written back or Google resets it.
                val body = buildJsonObject {
                    putJsonArray("attendees") {
                        event.attendees.forEach { attendee ->
                            // Google cannot store an attendee without an address, so one
                            // it sent email-less cannot be written back; fabricating an
                            // address fails the whole PATCH with "Invalid attendee email".
                            val email = attendee.email ?: return@forEach
                            addJsonObject {
                                put("email", email)
                                attendee.displayName?.let { put("displayName", it) }
                                if (attendee.optional) put("optional", true)
                                val status = if (attendee.self) response else attendee.responseStatus
                                status?.let { put("responseStatus", it) }
                            }
                        }
                    }
                }
                val updated = api.patchEvent(event.calendarId, event.id, body, notifyGuests = prefs.notifyGuests)
                cacheWrittenEvent(event.calendarId, updated)
            }
        }

    /** Writes a freshly returned API event into the cache and returns the cached shape. */
    private fun cacheWrittenEvent(
        calendarId: String,
        apiEvent: ApiEvent,
    ): EventItem {
        val calendarColor = store.calendar(calendarId)?.colorHex ?: "#4285F4"
        val item = apiEvent.toEventItem(calendarId, calendarColor, eventColors())
            ?: throw IllegalStateException("Google returned an event this app cannot display.")
        store.upsertEvent(item)
        notifyChanged()
        return item
    }

    // ---- remote search (covers events outside the cached window) --------

    suspend fun searchRemote(query: String): List<EventItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val colors = eventColors()
        val today = TimeUtil.today()
        val from = TimeUtil.toRfc3339(TimeUtil.startOfDay(today.minusYears(3)))
        val to = TimeUtil.toRfc3339(TimeUtil.startOfDay(today.plusYears(3)))

        store.calendars().filter { it.visible }.flatMap { calendar ->
            runCatching { api.searchEvents(calendar.id, query, from, to) }
                .getOrDefault(emptyList())
                .mapNotNull { it.toEventItem(calendar.id, calendar.colorHex, colors) }
        }.sortedByDescending { it.startMillis }
    }
}
