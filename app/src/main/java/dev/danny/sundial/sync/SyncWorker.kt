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
        container.reminderScheduler.rescheduleAll()

        return when {
            outcome.succeeded -> Result.success()
            outcome.calendarsSynced > 0 -> Result.success()
            runAttemptCount < 3 -> Result.retry()
            else -> Result.failure()
        }
    }
}
