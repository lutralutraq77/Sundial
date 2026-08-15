package dev.danny.sundial.data

import android.content.Context
import java.time.DayOfWeek

class AppPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("sundial_prefs", Context.MODE_PRIVATE)

    var defaultView: String
        get() = prefs.getString(KEY_DEFAULT_VIEW, "MONTH") ?: "MONTH"
        set(value) = prefs.edit().putString(KEY_DEFAULT_VIEW, value).apply()

    /** Minutes between background syncs; 0 means manual only. */
    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL, 30)
        set(value) = prefs.edit().putInt(KEY_SYNC_INTERVAL, value).apply()

    /** 0 follows the device locale, otherwise a java.time DayOfWeek value (1 = Monday). */
    var firstDayOfWeekValue: Int
        get() = prefs.getInt(KEY_FIRST_DAY, 0)
        set(value) = prefs.edit().putInt(KEY_FIRST_DAY, value).apply()

    val firstDayOfWeek: DayOfWeek
        get() = firstDayOfWeekValue.takeIf { it in 1..7 }?.let { DayOfWeek.of(it) }
            ?: java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek

    var defaultReminderMinutes: Int
        get() = prefs.getInt(KEY_DEFAULT_REMINDER, 10)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_REMINDER, value).apply()

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var showDeclined: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DECLINED, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DECLINED, value).apply()

    /** Notify guests by email when events with attendees are created or changed. */
    var notifyGuests: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_GUESTS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_GUESTS, value).apply()

    var lastSyncMillis: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var lastSyncError: String?
        get() = prefs.getString(KEY_LAST_SYNC_ERROR, null)
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_ERROR, value).apply()

    /** Days of history kept in the cache. */
    var windowDaysBack: Int
        get() = prefs.getInt(KEY_WINDOW_BACK, 180)
        set(value) = prefs.edit().putInt(KEY_WINDOW_BACK, value).apply()

    /** Days into the future kept in the cache. */
    var windowDaysForward: Int
        get() = prefs.getInt(KEY_WINDOW_FORWARD, 540)
        set(value) = prefs.edit().putInt(KEY_WINDOW_FORWARD, value).apply()

    var scheduledAlarmIds: Set<String>
        get() = prefs.getStringSet(KEY_ALARM_IDS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_ALARM_IDS, value).apply()

    private companion object {
        const val KEY_DEFAULT_VIEW = "default_view"
        const val KEY_SYNC_INTERVAL = "sync_interval"
        const val KEY_FIRST_DAY = "first_day_of_week"
        const val KEY_DEFAULT_REMINDER = "default_reminder"
        const val KEY_NOTIFICATIONS = "notifications_enabled"
        const val KEY_SHOW_DECLINED = "show_declined"
        const val KEY_NOTIFY_GUESTS = "notify_guests"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_LAST_SYNC_ERROR = "last_sync_error"
        const val KEY_WINDOW_BACK = "window_back"
        const val KEY_WINDOW_FORWARD = "window_forward"
        const val KEY_ALARM_IDS = "scheduled_alarm_ids"
    }
}
