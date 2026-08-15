package dev.danny.sundial.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.danny.sundial.R

object Notifications {

    const val CHANNEL_REMINDERS = "event_reminders"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val reminders = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminder_channel_desc)
            enableLights(true)
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(reminders)
    }
}
