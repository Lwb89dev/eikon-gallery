package app.eikon.gallery.data.indexing

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.eikon.gallery.core.di.ApplicationScope
import app.eikon.gallery.data.settings.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** One time slice of analysis. WorkManager allows about ten minutes, so it stops with a margin. */
@HiltWorker
class IndexingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val runner: IndexingRunner,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        runner.run(RUN_BUDGET_MS, manual = inputData.getBoolean(MANUAL, false))
        // Whatever is left is picked up by the next periodic run; progress is in the database.
        return Result.success()
    }

    companion object {
        /** Input data of a run the user asked for. */
        const val MANUAL = "manual"
        private const val RUN_BUDGET_MS = 8 * 60 * 1000L
    }
}

/**
 * Keeps WorkManager's schedule in step with the analysis settings. Analysis is periodic background
 * work constrained to a battery that is not low (and, by default, to charging), so it never competes
 * with normal use of the phone.
 */
@Singleton
class IndexingScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Call once at startup; re-applies the schedule whenever the analysis settings change. */
    fun start() {
        scope.launch {
            settings.state.filterNotNull().map { it.analysis }.distinctUntilChanged().collect { apply(SchedulePolicy.decide(it)) }
        }
    }

    /** Runs a slice right away (still not on a low battery, but even in Battery Saver: the user asked), for the "Analyze now" button. */
    fun analyzeNow() {
        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .setInputData(workDataOf(IndexingWorker.MANUAL to true))
            .build()
        workManager.enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun apply(decision: ScheduleDecision) {
        when (decision) {
            ScheduleDecision.Cancel -> {
                workManager.cancelUniqueWork(PERIODIC_WORK)
                workManager.cancelUniqueWork(NOW_WORK)
            }
            is ScheduleDecision.Periodic -> schedule(decision.requiresCharging)
        }
    }

    private fun schedule(requiresCharging: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(requiresCharging)
            .build()
        val request = PeriodicWorkRequestBuilder<IndexingWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    companion object {
        const val PERIODIC_WORK = "eikon-analysis"
        const val NOW_WORK = "eikon-analysis-now"
        private const val PERIOD_MINUTES = 15L
    }
}
