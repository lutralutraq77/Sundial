package dev.danny.sundial.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.danny.sundial.SundialApp

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SundialApp ?: return Result.success()
        val container = app.container

        if (!container.auth.state.value.signedIn) return Result.success()

        val outcome = container.syncEngine.sync()
        // A sign-out can land while the sync was in flight; don't re-arm alarms from
        // rows the cleanup is deleting.
        if (container.auth.state.value.signedIn) {
            container.reminderScheduler.rescheduleAll()
        }

        return when {
            outcome.succeeded -> Result.success()
            // Sync wiped the session (hard 401 / dead grant): retrying cannot help.
            !container.auth.state.value.signedIn -> Result.failure()
            // Partial failures retry with backoff — a transient per-calendar timeout
            // used to be reported as success and left that calendar stale for a
            // whole sync period. The windowed mark-and-sweep is idempotent.
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}
