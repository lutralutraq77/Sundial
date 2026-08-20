package dev.danny.sundial.ics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class IcsParserTest {

    /**
     * Built with appendLine rather than an interpolated raw string: trimIndent() runs
     * *after* interpolation, so a multi-line body would leave every line indented — and
     * a leading space is exactly how RFC 5545 marks a folded continuation line.
     */
    private fun wrap(body: String) = buildString {
        appendLine("BEGIN:VCALENDAR")
        appendLine("VERSION:2.0")
        appendLine("PRODID:-//Test//EN")
        appendLine(body.trimIndent())
        appendLine("END:VCALENDAR")
    }

    @Test
    fun `parses a timed event with a TZID`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:abc-123
            DTSTART;TZID=Europe/London:20260814T100000
            DTEND;TZID=Europe/London:20260814T113000
            SUMMARY:Standup
            LOCATION:Room 2
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()

        assertEquals("abc-123", event.uid)
        assertEquals("Standup", event.summary)
        assertEquals("Room 2", event.location)
        assertEquals("Europe/London", event.timeZone)
        assertEquals(false, event.allDay)

        val expected = ZonedDateTime.of(2026, 8, 14, 10, 0, 0, 0, ZoneId.of("Europe/London"))
        assertEquals(expected.toInstant().toEpochMilli(), event.startMillis)
        assertEquals(90 * 60_000L, event.endMillis!! - event.startMillis!!)
    }

    @Test
    fun `parses UTC timestamps`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:utc-1
            DTSTART:20260814T090000Z
            DTEND:20260814T100000Z
            SUMMARY:UTC event
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        assertEquals(Instant.parse("2026-08-14T09:00:00Z").toEpochMilli(), event.startMillis)
        assertEquals("UTC", event.timeZone)
    }

    @Test
    fun `all-day events keep the exclusive end date`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:holiday
            DTSTART;VALUE=DATE:20260814
            DTEND;VALUE=DATE:20260817
            SUMMARY:Long weekend
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        assertTrue(event.allDay)
        assertEquals("2026-08-14", event.startDate)
        assertEquals("2026-08-17", event.endDateExclusive)
    }

    @Test
    fun `a single-day all-day event without DTEND gets the next day`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:one-day
            DTSTART;VALUE=DATE:20260814
            SUMMARY:Day off
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        assertEquals("2026-08-14", event.startDate)
        assertEquals("2026-08-15", event.endDateExclusive)
    }

    @Test
    fun `unfolds continuation lines`() {
        val ics = "BEGIN:VCALENDAR\r\n" +
            "BEGIN:VEVENT\r\n" +
            "UID:folded\r\n" +
            "DTSTART:20260814T090000Z\r\n" +
            "DTEND:20260814T100000Z\r\n" +
            "SUMMARY:A very long title that has been\r\n" +
            "  wrapped across lines\r\n" +
            "END:VEVENT\r\n" +
            "END:VCALENDAR\r\n"

        // Folding inserts CRLF + one space, so unfolding removes exactly one character.
        val event = IcsParser.parse(ics).events.single()
        assertEquals("A very long title that has been wrapped across lines", event.summary)
    }

    @Test
    fun `unescapes TEXT values`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:escaped
            DTSTART:20260814T090000Z
            DTEND:20260814T100000Z
            SUMMARY:Lunch\, then coffee\; maybe
            DESCRIPTION:First line\nSecond line\\done
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        assertEquals("Lunch, then coffee; maybe", event.summary)
        assertEquals("First line\nSecond line\\done", event.description)
    }

    @Test
    fun `uses DURATION when DTEND is absent`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:dur
            DTSTART:20260814T090000Z
            DURATION:PT1H30M
            SUMMARY:Workshop
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        assertEquals(90 * 60_000L, event.endMillis!! - event.startMillis!!)
    }

    @Test
    fun `reads VALARM triggers as minutes before`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:alarmed
            DTSTART:20260814T090000Z
            DTEND:20260814T100000Z
            SUMMARY:With reminders
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Reminder
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-P1D
            END:VALARM
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        assertEquals(listOf(15, 1440), event.reminderMinutes)
    }

    @Test
    fun `ignores absolute alarm triggers`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:abs-alarm
            DTSTART:20260814T090000Z
            DTEND:20260814T100000Z
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;VALUE=DATE-TIME:20260814T080000Z
            END:VALARM
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        assertEquals(emptyList<Int>(), event.reminderMinutes)
    }

    @Test
    fun `keeps recurrence rules and exception dates`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:weekly
            DTSTART;TZID=Europe/London:20260814T090000
            DTEND;TZID=Europe/London:20260814T093000
            RRULE:FREQ=WEEKLY;BYDAY=FR
            EXDATE;TZID=Europe/London:20260821T090000
            SUMMARY:Weekly sync
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        assertEquals("RRULE:FREQ=WEEKLY;BYDAY=FR", event.recurrence[0])
        assertEquals("EXDATE;TZID=Europe/London:20260821T090000", event.recurrence[1])
    }

    @Test
    fun `flags modified occurrences so the importer can skip them`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:weekly
            RECURRENCE-ID;TZID=Europe/London:20260821T090000
            DTSTART;TZID=Europe/London:20260821T100000
            DTEND;TZID=Europe/London:20260821T103000
            SUMMARY:Moved this week
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        assertTrue(event.isOverride)
    }

    @Test
    fun `skips events with no DTSTART and reports a warning`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:broken
            SUMMARY:No start
            END:VEVENT
            """.trimIndent(),
        )

        val result = IcsParser.parse(ics)
        assertEquals(0, result.events.size)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `reads the calendar name`() {
        val ics = wrap(
            """
            X-WR-CALNAME:Work calendar
            BEGIN:VEVENT
            UID:x
            DTSTART:20260814T090000Z
            DTEND:20260814T100000Z
            END:VEVENT
            """.trimIndent(),
        )

        assertEquals("Work calendar", IcsParser.parse(ics).calendarName)
    }

    @Test
    fun `parses multiple events`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:one
            DTSTART:20260814T090000Z
            DTEND:20260814T100000Z
            SUMMARY:One
            END:VEVENT
            BEGIN:VEVENT
            UID:two
            DTSTART;VALUE=DATE:20260815
            SUMMARY:Two
            END:VEVENT
            """.trimIndent(),
        )

        val events = IcsParser.parse(ics).events
        assertEquals(2, events.size)
        assertEquals("One", events[0].summary)
        assertEquals("Two", events[1].summary)
        assertTrue(events[1].allDay)
    }

    @Test
    fun `parses ISO 8601 durations`() {
        assertEquals(90L, IcsParser.parseDurationMinutes("PT1H30M"))
        assertEquals(-15L, IcsParser.parseDurationMinutes("-PT15M"))
        assertEquals(-1440L, IcsParser.parseDurationMinutes("-P1D"))
        assertEquals(10080L, IcsParser.parseDurationMinutes("P1W"))
        assertEquals(0L, IcsParser.parseDurationMinutes("PT30S"))
        assertNull(IcsParser.parseDurationMinutes("nonsense"))
    }

    @Test
    fun `keeps quoted parameter values intact`() {
        val property = IcsParser.property("""DTSTART;TZID="Europe/Berlin":20260814T100000""")
        assertEquals("DTSTART", property.name)
        assertEquals("Europe/Berlin", property.params["TZID"])
        assertEquals("20260814T100000", property.value)
    }

    @Test
    fun `handles a real-world export shape`() {
        val ics = """
            BEGIN:VCALENDAR
            PRODID:-//Google Inc//Google Calendar 70.9054//EN
            VERSION:2.0
            CALSCALE:GREGORIAN
            METHOD:PUBLISH
            X-WR-CALNAME:danny@example.com
            X-WR-TIMEZONE:Europe/London
            BEGIN:VEVENT
            DTSTART;TZID=Europe/London:20260901T140000
            DTEND;TZID=Europe/London:20260901T150000
            RRULE:FREQ=WEEKLY;WKST=MO;UNTIL=20261201T235959Z;BYDAY=TU
            DTSTAMP:20260814T083000Z
            UID:6f9k2l3m4n@google.com
            CREATED:20260801T120000Z
            LAST-MODIFIED:20260810T090000Z
            SEQUENCE:1
            STATUS:CONFIRMED
            SUMMARY:Weekly review
            TRANSP:OPAQUE
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val result = IcsParser.parse(ics)
        val event = result.events.single()

        assertEquals("danny@example.com", result.calendarName)
        assertEquals("Weekly review", event.summary)
        assertEquals("confirmed", event.status)
        assertEquals(false, event.transparent)
        assertNotNull(event.startMillis)
        assertTrue(event.recurrence.single().startsWith("RRULE:FREQ=WEEKLY"))
    }

    @Test
    fun `resolves Windows TZIDs from Outlook exports`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:outlook-1
            DTSTART;TZID=W. Europe Standard Time:20260901T140000
            DTEND;TZID=W. Europe Standard Time:20260901T150000
            SUMMARY:Outlook meeting
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        assertEquals("Europe/Berlin", event.timeZone)
        val expected = ZonedDateTime.of(2026, 9, 1, 14, 0, 0, 0, ZoneId.of("Europe/Berlin"))
        assertEquals(expected.toInstant().toEpochMilli(), event.startMillis)
    }

    @Test
    fun `skips an event with an unknown TZID instead of importing it at the wrong time`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:custom-tz
            DTSTART;TZID=My Custom Zone:20260901T140000
            SUMMARY:Wrong zone
            END:VEVENT
            """.trimIndent(),
        )

        val result = IcsParser.parse(ics)
        assertTrue(result.events.isEmpty())
        assertTrue(result.warnings.single(), result.warnings.single().contains("My Custom Zone"))
    }

    @Test
    fun `rewrites Windows TZIDs in EXDATE lines to IANA ids`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:exdate-1
            DTSTART;TZID=Pacific Standard Time:20260901T090000
            RRULE:FREQ=DAILY
            EXDATE;TZID=Pacific Standard Time:20260902T090000
            SUMMARY:Daily thing
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        val exdate = event.recurrence.single { it.startsWith("EXDATE") }
        assertTrue(exdate, exdate.contains("TZID=America/Los_Angeles"))
    }

    @Test
    fun `skips end-relative alarm triggers`() {
        val ics = wrap(
            """
            BEGIN:VEVENT
            UID:alarm-end
            DTSTART:20260901T140000Z
            DTEND:20260901T160000Z
            SUMMARY:Long meeting
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;RELATED=END:-PT15M
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT10M
            END:VALARM
            END:VEVENT
            """.trimIndent(),
        )

        val event = IcsParser.parse(ics).events.single()
        // Only the start-relative alarm survives; the end-relative one has no
        // equivalent in Google's minutes-before-start model.
        assertEquals(listOf(10), event.reminderMinutes)
    }
}
