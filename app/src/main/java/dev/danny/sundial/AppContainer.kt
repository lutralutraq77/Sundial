package dev.danny.sundial

import android.content.Context
import dev.danny.sundial.auth.AuthRepository
import dev.danny.sundial.data.AppPrefs
import dev.danny.sundial.data.CalendarRepository
import dev.danny.sundial.data.LocalStore
import dev.danny.sundial.data.SyncEngine
import dev.danny.sundial.ics.IcsEvent
import dev.danny.sundial.ics.IcsImporter
import dev.danny.sundial.ics.ImportResult
import dev.danny.sundial.net.CalendarApi
import dev.danny.sundial.reminders.ReminderScheduler
import dev.danny.sundial.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of an .ics import running in the app scope; survives screen recreation. */
sealed interface ImportRun {
    data class Running(val done: Int, val total: Int) : ImportRun
    data class Finished(val result: ImportResult) : ImportRun
}

/** Manual dependency graph — the app is small enough that a DI framework would be noise. */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** Work that must survive any screen being removed from composition. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs = AppPrefs(appContext)
    val store = LocalStore(appContext)
    val auth = AuthRepository(appContext)
    val api = CalendarApi(auth)
    val repository = CalendarRepository(api, store, prefs)
    val syncEngine = SyncEngine(api, store, prefs, repository)
    val reminderScheduler = ReminderScheduler(appContext, store, prefs)
    val importer = IcsImporter(api, repository)

    init {
        // Sign-in/out side-effects live here, NOT in a composable: the recomposition
        // that delivers a signedIn flip removes the very screen whose LaunchedEffect
        // would have run them. This collector is the single owner of both directions,
        // so it also covers the auth layer force-signing-out on its own (a dead grant
        // discovered during refresh) — without it the periodic job and reminder
        // alarms would keep firing for an account the app decided is signed out.
        // drop(1) skips the value at process start: SundialApp already ensures
        // periodic sync then, and CalendarViewModel owns the visible foreground sync.
        appScope.launch {
            auth.state.map { it.signedIn }.distinctUntilChanged().drop(1).collect { signedIn ->
                if (signedIn) {
                    SyncScheduler.ensurePeriodic(appContext, prefs.syncIntervalMinutes)
                } else {
                    SyncScheduler.cancelAll(appContext)
                    // Under the sync lock: a foreground sync already past its fetch
                    // would otherwise upsert the old account's events AFTER this wipe.
                    withContext(Dispatchers.IO) { syncEngine.withSyncLock { clearLocalData() } }
                }
            }
        }
    }

    /**
     * Sign-out must not run in a composition scope: flipping signedIn recomposes the
     * root and cancels the Settings screen's scope before cleanup would finish.
     * auth.signOut() drops the local session first (the UI swaps to setup at once,
     * and the collector above cancels sync work and wipes the cache on that flip),
     * then revokes at Google's end best-effort.
     */
    fun signOut() {
        appScope.launch { auth.signOut() }
    }

    private val _importRun = MutableStateFlow<ImportRun?>(null)

    /** The in-flight or just-finished .ics import; a recreated screen re-attaches here. */
    val importRun: StateFlow<ImportRun?> = _importRun.asStateFlow()

    /**
     * Runs an .ics import to completion even if the import screen is left mid-way — a
     * composition-scoped coroutine would be cancelled by Back or rotation, silently
     * truncating the import partway through its per-event network calls. Progress and
     * result live in [importRun] rather than the screen's state for the same reason.
     */
    fun runImport(events: List<IcsEvent>, calendarId: String) {
        if (_importRun.value is ImportRun.Running) return
        _importRun.value = ImportRun.Running(0, events.size)
        appScope.launch {
            val result = runCatching {
                importer.import(events, calendarId) { done, total ->
                    _importRun.value = ImportRun.Running(done, total)
                }
            }.getOrElse { failure ->
                ImportResult(
                    imported = 0,
                    skipped = 0,
                    failed = events.size,
                    messages = listOf(failure.message ?: "Import failed."),
                )
            }
            _importRun.value = ImportRun.Finished(result)
        }
    }

    /** Retires a finished import once its result has been shown. */
    fun clearImportRun() {
        if (_importRun.value is ImportRun.Finished) _importRun.value = null
    }

    /** Wipes cached calendar data; used when signing out. */
    fun clearLocalData() {
        reminderScheduler.cancelAll()
        store.clearAll()
        prefs.lastSyncMillis = 0
        prefs.lastSyncError = null
        repository.notifyChanged()
    }
}
