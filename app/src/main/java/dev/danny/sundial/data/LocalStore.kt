package dev.danny.sundial.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.danny.sundial.core.Attendee
import dev.danny.sundial.core.CalendarInfo
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.core.EventReminders
import dev.danny.sundial.core.Reminder

/**
 * The on-device event cache. Hand-rolled SQLite rather than Room: three tables and a
 * dozen queries do not justify an annotation processor, and the cache is disposable —
 * any schema change just drops it and resyncs.
 */
class LocalStore(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE calendars (
                id TEXT PRIMARY KEY NOT NULL,
                summary TEXT NOT NULL,
                description TEXT,
                timeZone TEXT,
                colorHex TEXT NOT NULL,
                accessRole TEXT NOT NULL,
                isPrimary INTEGER NOT NULL DEFAULT 0,
                visible INTEGER NOT NULL DEFAULT 1,
                defaultReminders TEXT NOT NULL DEFAULT '',
                sortOrder INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE events (
                id TEXT NOT NULL,
                calendarId TEXT NOT NULL,
                summary TEXT,
                description TEXT,
                location TEXT,
                allDay INTEGER NOT NULL DEFAULT 0,
                startMillis INTEGER NOT NULL,
                endMillis INTEGER NOT NULL,
                startDate TEXT,
                endDate TEXT,
                timeZone TEXT,
                status TEXT,
                htmlLink TEXT,
                colorId TEXT,
                colorHex TEXT NOT NULL DEFAULT '#4285F4',
                recurringEventId TEXT,
                recurrence TEXT,
                organizer TEXT,
                attendees TEXT,
                remindersUseDefault INTEGER NOT NULL DEFAULT 1,
                remindersOverrides TEXT,
                hangoutLink TEXT,
                updatedMillis INTEGER NOT NULL DEFAULT 0,
                busy INTEGER NOT NULL DEFAULT 1,
                syncMark INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (calendarId, id)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_events_range ON events(startMillis, endMillis)")
        db.execSQL("CREATE INDEX idx_events_calendar ON events(calendarId)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY NOT NULL, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            // Events are disposable, but calendars.visible is a local preference a
            // drop-and-recreate would silently reset — alter in place instead.
            db.execSQL("ALTER TABLE events ADD COLUMN busy INTEGER NOT NULL DEFAULT 1")
            return
        }
        db.execSQL("DROP TABLE IF EXISTS events")
        db.execSQL("DROP TABLE IF EXISTS calendars")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    // ---- calendars -----------------------------------------------------

    fun upsertCalendars(calendars: List<CalendarInfo>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val known = calendars.map { it.id }.toSet()
            calendars.forEachIndexed { index, calendar ->
                // Visibility is a local preference; keep whatever the user chose.
                val existing = calendarVisibility(db, calendar.id)
                val values = ContentValues().apply {
                    put("id", calendar.id)
                    put("summary", calendar.summary)
                    put("description", calendar.description)
                    put("timeZone", calendar.timeZone)
                    put("colorHex", calendar.colorHex)
                    put("accessRole", calendar.accessRole)
                    put("isPrimary", if (calendar.isPrimary) 1 else 0)
                    put("visible", if (existing ?: calendar.visible) 1 else 0)
                    put("defaultReminders", encodeReminders(calendar.defaultReminders))
                    put("sortOrder", index)
                }
                db.insertWithOnConflict("calendars", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            if (known.isNotEmpty()) {
                val placeholders = known.joinToString(",") { "?" }
                db.delete("calendars", "id NOT IN ($placeholders)", known.toTypedArray())
                db.delete("events", "calendarId NOT IN ($placeholders)", known.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun calendarVisibility(db: SQLiteDatabase, id: String): Boolean? =
        db.query("calendars", arrayOf("visible"), "id = ?", arrayOf(id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) == 1 else null
        }

    fun calendars(): List<CalendarInfo> =
        readableDatabase.query("calendars", null, null, null, null, null, "isPrimary DESC, sortOrder ASC")
            .use { cursor ->
                buildList { while (cursor.moveToNext()) add(readCalendar(cursor)) }
            }

    fun calendar(id: String): CalendarInfo? =
        readableDatabase.query("calendars", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) readCalendar(cursor) else null
        }

    fun setCalendarVisible(id: String, visible: Boolean) {
        writableDatabase.update(
            "calendars",
            ContentValues().apply { put("visible", if (visible) 1 else 0) },
            "id = ?",
            arrayOf(id),
        )
    }

    // ---- events --------------------------------------------------------

    fun upsertEvents(events: List<EventItem>, syncMark: Long) {
        if (events.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            events.forEach { event ->
                db.insertWithOnConflict("events", null, eventValues(event, syncMark), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun upsertEvent(event: EventItem) {
        writableDatabase.insertWithOnConflict(
            "events",
            null,
            eventValues(event, System.currentTimeMillis()),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /**
     * Removes rows in the synced window that this run did not touch — the mark-and-sweep
     * half of a windowed full sync, which is how deletions made elsewhere reach us.
     */
    fun deleteUnmarked(calendarId: String, windowStart: Long, windowEnd: Long, syncMark: Long): Int =
        writableDatabase.delete(
            "events",
            // Strictly older marks only: rows stamped at or after the run began are
            // local writes made mid-sync, and sweeping those deletes an event the
            // user just saved. A later sync re-confirms or sweeps them properly.
            "calendarId = ? AND syncMark < ? AND endMillis > ? AND startMillis < ?",
            arrayOf(calendarId, syncMark.toString(), windowStart.toString(), windowEnd.toString()),
        )

    /** Drops cached rows that fell out of the synced window, so the cache stays bounded. */
    fun pruneOutsideWindow(windowStart: Long, windowEnd: Long): Int =
        writableDatabase.delete(
            "events",
            "endMillis <= ? OR startMillis >= ?",
            arrayOf(windowStart.toString(), windowEnd.toString()),
        )

    fun deleteEvent(calendarId: String, eventId: String) {
        writableDatabase.delete("events", "calendarId = ? AND id = ?", arrayOf(calendarId, eventId))
    }

    fun deleteRecurringSeries(calendarId: String, recurringEventId: String) {
        writableDatabase.delete(
            "events",
            "calendarId = ? AND (recurringEventId = ? OR id = ?)",
            arrayOf(calendarId, recurringEventId, recurringEventId),
        )
    }

    /** Events overlapping [startMillis, endMillis), ordered for display. */
    fun eventsBetween(startMillis: Long, endMillis: Long, visibleOnly: Boolean = true): List<EventItem> {
        val visibilityClause = if (visibleOnly) {
            " AND calendarId IN (SELECT id FROM calendars WHERE visible = 1)"
        } else {
            ""
        }
        val sql = """
            SELECT * FROM events
            WHERE endMillis > ? AND startMillis < ?$visibilityClause
            ORDER BY allDay DESC, startMillis ASC, endMillis DESC
        """.trimIndent()
        return readableDatabase.rawQuery(sql, arrayOf(startMillis.toString(), endMillis.toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(readEvent(cursor)) } }
    }

    fun event(calendarId: String, eventId: String): EventItem? =
        readableDatabase.query("events", null, "calendarId = ? AND id = ?", arrayOf(calendarId, eventId), null, null, null)
            .use { cursor -> if (cursor.moveToFirst()) readEvent(cursor) else null }

    fun search(query: String, limit: Int = 200): List<EventItem> {
        // % and _ in the user's text are literals, not LIKE wildcards.
        val escaped = query.trim()
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val needle = "%$escaped%"
        val sql = """
            SELECT * FROM events
            WHERE (summary LIKE ? ESCAPE '\' OR description LIKE ? ESCAPE '\' OR location LIKE ? ESCAPE '\')
              AND calendarId IN (SELECT id FROM calendars WHERE visible = 1)
            ORDER BY startMillis DESC
            LIMIT ?
        """.trimIndent()
        return readableDatabase.rawQuery(sql, arrayOf(needle, needle, needle, limit.toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(readEvent(cursor)) } }
    }

    /** Timed and all-day events starting inside a window, used to schedule reminders. */
    fun eventsStartingBetween(fromMillis: Long, toMillis: Long): List<EventItem> {
        val sql = """
            SELECT * FROM events
            WHERE startMillis >= ? AND startMillis < ?
              AND (status IS NULL OR status != 'cancelled')
              AND calendarId IN (SELECT id FROM calendars WHERE visible = 1)
            ORDER BY startMillis ASC
        """.trimIndent()
        return readableDatabase.rawQuery(sql, arrayOf(fromMillis.toString(), toMillis.toString()))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(readEvent(cursor)) } }
    }

    fun eventCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    fun clearEvents() {
        writableDatabase.delete("events", null, null)
    }

    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("events", null, null)
            db.delete("calendars", null, null)
            db.delete("meta", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- meta ----------------------------------------------------------

    fun meta(key: String): String? =
        readableDatabase.query("meta", arrayOf("v"), "k = ?", arrayOf(key), null, null, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    fun setMeta(key: String, value: String?) {
        if (value == null) {
            writableDatabase.delete("meta", "k = ?", arrayOf(key))
            return
        }
        writableDatabase.insertWithOnConflict(
            "meta",
            null,
            ContentValues().apply {
                put("k", key)
                put("v", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---- row mapping ---------------------------------------------------

    private fun eventValues(event: EventItem, syncMark: Long) = ContentValues().apply {
        put("id", event.id)
        put("calendarId", event.calendarId)
        put("summary", event.summary)
        put("description", event.description)
        put("location", event.location)
        put("allDay", if (event.allDay) 1 else 0)
        put("startMillis", event.startMillis)
        put("endMillis", event.endMillis)
        put("startDate", event.startDate)
        put("endDate", event.endDate)
        put("timeZone", event.timeZone)
        put("status", event.status)
        put("htmlLink", event.htmlLink)
        put("colorId", event.colorId)
        put("colorHex", event.colorHex)
        put("recurringEventId", event.recurringEventId)
        put("recurrence", event.recurrence.joinToString("\n"))
        put("organizer", event.organizer)
        put("attendees", encodeAttendees(event.attendees))
        put("remindersUseDefault", if (event.reminders.useDefault) 1 else 0)
        put("remindersOverrides", encodeReminders(event.reminders.overrides))
        put("hangoutLink", event.hangoutLink)
        put("updatedMillis", event.updatedMillis)
        put("busy", if (event.busy) 1 else 0)
        put("syncMark", syncMark)
    }

    private fun readCalendar(cursor: Cursor) = CalendarInfo(
        id = cursor.string("id").orEmpty(),
        summary = cursor.string("summary").orEmpty(),
        description = cursor.string("description"),
        timeZone = cursor.string("timeZone"),
        colorHex = cursor.string("colorHex") ?: "#4285F4",
        accessRole = cursor.string("accessRole") ?: "reader",
        isPrimary = cursor.int("isPrimary") == 1,
        visible = cursor.int("visible") == 1,
        defaultReminders = decodeReminders(cursor.string("defaultReminders")),
    )

    private fun readEvent(cursor: Cursor) = EventItem(
        id = cursor.string("id").orEmpty(),
        calendarId = cursor.string("calendarId").orEmpty(),
        summary = cursor.string("summary"),
        description = cursor.string("description"),
        location = cursor.string("location"),
        allDay = cursor.int("allDay") == 1,
        startMillis = cursor.long("startMillis"),
        endMillis = cursor.long("endMillis"),
        startDate = cursor.string("startDate"),
        endDate = cursor.string("endDate"),
        timeZone = cursor.string("timeZone"),
        status = cursor.string("status"),
        htmlLink = cursor.string("htmlLink"),
        colorId = cursor.string("colorId"),
        recurringEventId = cursor.string("recurringEventId"),
        recurrence = cursor.string("recurrence")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
        organizer = cursor.string("organizer"),
        attendees = decodeAttendees(cursor.string("attendees")),
        reminders = EventReminders(
            useDefault = cursor.int("remindersUseDefault") == 1,
            overrides = decodeReminders(cursor.string("remindersOverrides")),
        ),
        hangoutLink = cursor.string("hangoutLink"),
        updatedMillis = cursor.long("updatedMillis"),
        colorHex = cursor.string("colorHex") ?: "#4285F4",
        busy = cursor.int("busy") == 1,
    )

    private fun Cursor.string(name: String): String? {
        val index = getColumnIndex(name)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.int(name: String): Int {
        val index = getColumnIndex(name)
        return if (index < 0 || isNull(index)) 0 else getInt(index)
    }

    private fun Cursor.long(name: String): Long {
        val index = getColumnIndex(name)
        return if (index < 0 || isNull(index)) 0L else getLong(index)
    }

    // ---- tiny value encodings -----------------------------------------

    private fun encodeReminders(reminders: List<Reminder>): String =
        reminders.joinToString(",") { "${it.method}:${it.minutes}" }

    private fun decodeReminders(raw: String?): List<Reminder> =
        raw?.split(',')
            ?.mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val method = part.substringBefore(':', "popup")
                val minutes = part.substringAfter(':', "").toIntOrNull() ?: return@mapNotNull null
                Reminder(method.ifBlank { "popup" }, minutes)
            }
            ?: emptyList()

    /** Attendees are stored as tab/newline delimited records — no JSON parser on the read path. */
    private fun encodeAttendees(attendees: List<Attendee>): String =
        attendees.joinToString("\n") { attendee ->
            listOf(
                attendee.email.orEmpty(),
                attendee.displayName.orEmpty(),
                attendee.responseStatus.orEmpty(),
                if (attendee.self) "1" else "0",
                if (attendee.organizer) "1" else "0",
                if (attendee.optional) "1" else "0",
            ).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        }

    private fun decodeAttendees(raw: String?): List<Attendee> =
        raw?.split('\n')
            ?.mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                val parts = line.split('\t')
                Attendee(
                    email = parts.getOrNull(0)?.takeIf { it.isNotBlank() },
                    displayName = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                    responseStatus = parts.getOrNull(2)?.takeIf { it.isNotBlank() },
                    self = parts.getOrNull(3) == "1",
                    organizer = parts.getOrNull(4) == "1",
                    optional = parts.getOrNull(5) == "1",
                )
            }
            ?: emptyList()

    private companion object {
        const val NAME = "sundial.db"
        const val VERSION = 2
    }
}
