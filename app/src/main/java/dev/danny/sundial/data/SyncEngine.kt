package dev.danny.sundial.data

import android.util.Log
import dev.danny.sundial.auth.AuthException
import dev.danny.sundial.core.SyncOutcome
import dev.danny.sundial.core.TimeUtil
import dev.danny.sundial.net.ApiException
import dev.danny.sundial.net.CalendarApi
import dev.danny.sundial.net.NetworkFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Windowed full sync.
 *
 * Google's incremental syncToken cannot be combined with timeMin/timeMax, and it only
 * yields master events — using it would mean carrying a full RRULE expander on device.
 * Instead each run re-reads a bounded window with singleEvents=true and mark-and-sweeps
 * the cache, which is a few requests for a personal calendar and is always correct,
 * including for events deleted or moved elsewhere.
 */
class SyncEngine(
    private val api: CalendarApi,
    private val store: LocalStore,
    private val prefs: AppPrefs,
    private val repository: CalendarRepository,
) {

    private val mutex = Mutex()

    suspend fun sync(): SyncOutcome = mutex.withLock {
        withContext(Dispatchers.IO) { runSync() }
    }

    private suspend fun runSync(): SyncOutcome {
        val startedAt = System.currentTimeMillis()

        val calendars = try {
            val entries = api.calendarList().map { it.toCalendarInfo() }
            store.upsertCalendars(entries)
            store.calendars()
        } catch (e: AuthException) {
            return finish(startedAt, 0, 0, e.message ?: "Sign-in expired.")
        } catch (e: Exception) {
            return finish(startedAt, 0, 0, describe(e))
        }

        val eventColors = runCatching { api.colors() }
            .getOrNull()
            ?.event
            ?.mapValues { (_, pair) -> pair.background }
            ?: readCachedEventColors()
        cacheEventColors(eventColors)

        val today = TimeUtil.today()
        val windowStart = TimeUtil.startOfDay(today.minusDays(prefs.windowDaysBack.toLong()))
        val windowEnd = TimeUtil.startOfDay(today.plusDays(prefs.windowDaysForward.toLong()))
        val timeMin = TimeUtil.toRfc3339(windowStart)
        val timeMax = TimeUtil.toRfc3339(windowEnd)
        val mark = startedAt

        var written = 0
        var synced = 0
        val failures = mutableListOf<String>()

        for (calendar in calendars) {
            try {
                var pageToken: String? = null
                do {
                    val page = api.events(calendar.id, timeMin, timeMax, pageToken)
                    val items = page.items.mapNotNull {
                        it.toEventItem(calendar.id, calendar.colorHex, eventColors)
                    }
                    store.upsertEvents(items, mark)
                    written += items.size
                    pageToken = page.nextPageToken
                } while (pageToken != null)

                store.deleteUnmarked(calendar.id, windowStart, windowEnd, mark)
                synced++
            } catch (e: AuthException) {
                return finish(startedAt, synced, written, e.message ?: "Sign-in expired.")
            } catch (e: ApiException) {
                // A single unreadable calendar should not sink the whole sync.
                Log.w(TAG, "Skipping calendar ${calendar.id}: ${e.message}")
                failures += "${calendar.summary}: ${e.message}"
            } catch (e: Exception) {
                Log.w(TAG, "Skipping calendar ${calendar.id}", e)
                failures += "${calendar.summary}: ${describe(e)}"
            }
        }

        store.pruneOutsideWindow(windowStart, windowEnd)
        repository.notifyChanged()

        val error = when {
            failures.isEmpty() -> null
            synced == 0 -> failures.first()
            else -> "Some calendars did not sync: ${failures.joinToString("; ")}"
        }
        return finish(startedAt, synced, written, error)
    }

    private fun finish(startedAt: Long, calendars: Int, events: Int, error: String?): SyncOutcome {
        val finishedAt = System.currentTimeMillis()
        if (error == null || calendars > 0) prefs.lastSyncMillis = finishedAt
        prefs.lastSyncError = error
        return SyncOutcome(
            calendarsSynced = calendars,
            eventsWritten = events,
            startedAt = startedAt,
            finishedAt = finishedAt,
            error = error,
        )
    }

    // Was: "No network access (offline, or Network permission off)." -- an assertion
    // about a permission this code never checked, drawn from an exception type that
    // does not indicate it. NetworkFailure states what was observed instead; a denied
    // permission has its own signature (EACCES from socket(2)) and gets named only when
    // that is what actually happened.
    private fun describe(e: Exception): String = NetworkFailure.describe(e)

    private fun cacheEventColors(colors: Map<String, String>) {
        store.setMeta(META_EVENT_COLORS, colors.entries.joinToString(";") { "${it.key}=${it.value}" })
    }

    private fun readCachedEventColors(): Map<String, String> =
        store.meta(META_EVENT_COLORS)
            ?.split(';')
            ?.mapNotNull { entry ->
                val key = entry.substringBefore('=', "")
                val value = entry.substringAfter('=', "")
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            ?.toMap()
            ?: emptyMap()

    companion object {
        const val META_EVENT_COLORS = "event_colors"
        private const val TAG = "SundialSync"
    }
}
