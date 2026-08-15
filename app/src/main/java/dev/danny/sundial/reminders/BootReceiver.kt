package dev.danny.sundial.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.danny.sundial.SundialApp
import dev.danny.sundial.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Alarms do not survive a reboot, a package replacement or a time-zone change, so the
 * whole reminder set is re-armed from the cache whenever one of those happens.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> Unit
            else -> return
        }

        val app = context.applicationContext as? SundialApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.reminderScheduler.rescheduleAll()
                SyncScheduler.ensurePeriodic(context, app.container.prefs.syncIntervalMinutes)
                if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                    // All-day boundaries were stored in the old zone; a resync fixes them.
                    SyncScheduler.syncNow(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
