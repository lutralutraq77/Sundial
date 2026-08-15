package dev.danny.sundial

import android.app.Application
import dev.danny.sundial.reminders.Notifications
import dev.danny.sundial.sync.SyncScheduler

class SundialApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        if (container.auth.state.value.signedIn) {
            SyncScheduler.ensurePeriodic(this, container.prefs.syncIntervalMinutes)
        }
    }
}
