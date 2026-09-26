package app.eikon.gallery.data.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.eikon.gallery.data.Clock
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** One time slice of backup. WorkManager allows about ten minutes, so it stops with a margin; what is left is picked up by the next run, and progress is in the database. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val targets: BackupTargets,
    private val queue: BackupQueue,
    private val source: BackupSource,
    private val environment: DeviceBackupEnvironment,
    private val settings: BackupSettingsRepository,
    private val clock: Clock,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        environment.refresh()
        if (!environment.settings().enabled) return Result.success()
        val report = try {
            BackupRunner(queue, source, environment, targets.create()).run(RUN_BUDGET_MS, manual = inputData.getBoolean(MANUAL, false))
        } catch (e: BackupException.Unauthorized) {
            BackupReport(0, 0, 0, BackupStop.UNAUTHORIZED, e.message)
        } catch (e: BackupException) {
            BackupReport(0, 0, 0, BackupStop.MISCONFIGURED, e.message)
        }
        settings.recordRun(report, clock.nowMillis())
        return Result.success()
    }

    companion object {
        /** Input data of a run the user asked for. */
        const val MANUAL = "manual"
        private const val RUN_BUDGET_MS = 8 * 60 * 1000L
    }
}
