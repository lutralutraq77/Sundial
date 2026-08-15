package dev.danny.sundial

import android.content.Context
import dev.danny.sundial.auth.AuthRepository
import dev.danny.sundial.data.AppPrefs
import dev.danny.sundial.data.CalendarRepository
import dev.danny.sundial.data.LocalStore
import dev.danny.sundial.data.SyncEngine
import dev.danny.sundial.ics.IcsImporter
import dev.danny.sundial.net.CalendarApi
import dev.danny.sundial.reminders.ReminderScheduler

/** Manual dependency graph — the app is small enough that a DI framework would be noise. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val prefs = AppPrefs(appContext)
    val store = LocalStore(appContext)
    val auth = AuthRepository(appContext)
    val api = CalendarApi(auth)
    val repository = CalendarRepository(api, store, prefs)
    val syncEngine = SyncEngine(api, store, prefs, repository)
    val reminderScheduler = ReminderScheduler(appContext, store, prefs)
    val importer = IcsImporter(api, repository)

    /** Wipes cached calendar data; used when signing out. */
    fun clearLocalData() {
        reminderScheduler.cancelAll()
        store.clearAll()
        prefs.lastSyncMillis = 0
        prefs.lastSyncError = null
        repository.notifyChanged()
    }
}
