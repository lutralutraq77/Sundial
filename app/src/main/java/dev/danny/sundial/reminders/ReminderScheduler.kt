package dev.danny.sundial.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.danny.sundial.core.EventItem
import dev.danny.sundial.data.AppPrefs
import dev.danny.sundial.data.LocalStore

/**
 * Event reminders are local alarms. Without Play Services there is no FCM push, so
 * nothing can tell the device about an event in real time — reminders are scheduled
 * from whatever the last sync pulled down, and re-armed after every sync and reboot.
 */
class ReminderScheduler(
    private val context: Context,
    private val store: LocalStore,
    private val prefs: AppPrefs,
) {

    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun rescheduleAll() {
        val manager = alarmManager ?: return
        val previous = prefs.scheduledAlarmIds
        if (!prefs.notificationsEnabled) {
            cancelKeys(previous)
            prefs.scheduledAlarmIds = emptySet()
            return
        }

        val now = System.currentTimeMillis()
        val horizon = now + HORIZON_MS
        val calendars = store.calendars().associateBy { it.id }
        val events = store.eventsStartingBetween(now - GRACE_MS, horizon)

        val wanted = LinkedHashMap<String, ScheduledAlarm>()
        for (event in events) {
            val defaults = calendars[event.calendarId]?.defaultReminders.orEmpty()
            val reminders = if (event.reminders.useDefault) defaults else event.reminders.overrides
            reminders
                .filter { it.method == "popup" }
                .map { it.minutes }
                .distinct()
                .forEach { minutes ->
                    val triggerAt = event.startMillis - minutes * 60_000L
                    if (triggerAt <= now) return@forEach
                    val key = keyFor(event, minutes)
                    if (wanted.size < MAX_ALARMS) {
                        wanted[key] = ScheduledAlarm(key, event, minutes, triggerAt)
                    }
                }
        }

        // Drop alarms that are no longer wanted before arming the new set.
        cancelKeys(previous - wanted.keys)

        wanted.values.forEach { alarm ->
            val pendingIntent = pendingIntentFor(alarm, PendingIntent.FLAG_UPDATE_CURRENT)
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerAt, pendingIntent)
            } catch (e: SecurityException) {
                // Exact alarms withdrawn by the user; an inexact alarm still fires, late.
                Log.w(TAG, "Falling back to inexact alarm: ${e.message}")
                manager.set(AlarmManager.RTC_WAKEUP, alarm.triggerAt, pendingIntent)
            }
        }

        prefs.scheduledAlarmIds = wanted.keys.toSet()
    }

    fun cancelAll() {
        cancelKeys(prefs.scheduledAlarmIds)
        prefs.scheduledAlarmIds = emptySet()
    }

    private fun cancelKeys(keys: Set<String>) {
        val manager = alarmManager ?: return
        keys.forEach { key ->
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_REMIND
                data = android.net.Uri.parse("sundial://reminder/$key")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                key.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pendingIntent != null) {
                manager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
    }

    private fun pendingIntentFor(alarm: ScheduledAlarm, flags: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            // A distinct data URI keeps PendingIntents from colliding.
            data = android.net.Uri.parse("sundial://reminder/${alarm.key}")
            putExtra(EXTRA_CALENDAR_ID, alarm.event.calendarId)
            putExtra(EXTRA_EVENT_ID, alarm.event.id)
            putExtra(EXTRA_TITLE, alarm.event.title)
            putExtra(EXTRA_LOCATION, alarm.event.location)
            putExtra(EXTRA_START, alarm.event.startMillis)
            putExtra(EXTRA_END, alarm.event.endMillis)
            putExtra(EXTRA_ALL_DAY, alarm.event.allDay)
            putExtra(EXTRA_MINUTES, alarm.minutes)
        }
        return PendingIntent.getBroadcast(
            context,
            alarm.key.hashCode(),
            intent,
            flags or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun keyFor(event: EventItem, minutes: Int): String =
        "${event.calendarId}|${event.id}|$minutes"

    private data class ScheduledAlarm(
        val key: String,
        val event: EventItem,
        val minutes: Int,
        val triggerAt: Long,
    )

    companion object {
        const val ACTION_REMIND = "dev.danny.sundial.REMIND"
        const val EXTRA_CALENDAR_ID = "calendarId"
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_LOCATION = "location"
        const val EXTRA_START = "start"
        const val EXTRA_END = "end"
        const val EXTRA_ALL_DAY = "allDay"
        const val EXTRA_MINUTES = "minutes"

        private const val TAG = "SundialReminders"
        private const val HORIZON_MS = 7L * 24 * 60 * 60 * 1000
        private const val GRACE_MS = 60L * 60 * 1000
        private const val MAX_ALARMS = 150
    }
}
