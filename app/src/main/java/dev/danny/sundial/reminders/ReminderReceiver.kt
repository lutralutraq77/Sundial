package dev.danny.sundial.reminders

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.danny.sundial.MainActivity
import dev.danny.sundial.R
import dev.danny.sundial.core.TimeUtil

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMIND) return

        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val calendarId = intent.getStringExtra(ReminderScheduler.EXTRA_CALENDAR_ID) ?: return
        val eventId = intent.getStringExtra(ReminderScheduler.EXTRA_EVENT_ID) ?: return
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_TITLE) ?: "Event"
        val location = intent.getStringExtra(ReminderScheduler.EXTRA_LOCATION)
        val start = intent.getLongExtra(ReminderScheduler.EXTRA_START, 0L)
        val end = intent.getLongExtra(ReminderScheduler.EXTRA_END, 0L)
        val allDay = intent.getBooleanExtra(ReminderScheduler.EXTRA_ALL_DAY, false)

        val when1 = if (allDay) {
            TimeUtil.formatFullDate(TimeUtil.toLocalDate(start))
        } else {
            TimeUtil.formatRange(start, end, false)
        }
        val text = listOfNotNull(when1, location?.takeIf { it.isNotBlank() }).joinToString(" · ")

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_CALENDAR_ID, calendarId)
            putExtra(MainActivity.EXTRA_OPEN_EVENT_ID, eventId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            "$calendarId|$eventId".hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setWhen(start)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(context).notify("$calendarId|$eventId".hashCode(), notification)
    }
}
