package app.eikon.gallery.data.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.eikon.gallery.core.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The backup in the `backup` build: keeps WorkManager's schedule in step with the settings. Nothing is scheduled until the user turns the backup on, and turning it off cancels
 * everything. A run happens only when Android says the conditions the user chose hold (the network they allowed, the battery not low, charging if they asked for it).
 */
@Singleton
class HomeServerBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: BackupSettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : BackupService {
    override val isAvailable: Boolean = true

    private val workManager get() = WorkManager.getInstance(context)

    override fun start() {
        scope.launch { settings.settings.distinctUntilChanged().collect(::apply) }
    }

    /** True while a run (scheduled or asked for) is going. */
    // A getter, so that WorkManager is not started while the application's own objects are still being built.
    val isRunning: Flow<Boolean>
        get() = combine(infos(PERIODIC_WORK), infos(NOW_WORK)) { periodic, now -> (periodic + now).any { it.state == WorkInfo.State.RUNNING } }.distinctUntilChanged()

    private fun infos(name: String): Flow<List<WorkInfo>> = workManager.getWorkInfosForUniqueWorkFlow(name)

    /** A run right now, for the "Back up now" button: still on the network the user allowed and not on a low battery, but even in Battery Saver and not waiting for the charger. */
    fun backUpNow() {
        scope.launch {
            val current = settings.current()
            if (!current.enabled) return@launch
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(networkFor(current)).setRequiresBatteryNotLow(true).build())
                .setInputData(workDataOf(BackupWorker.MANUAL to true))
                .build()
            workManager.enqueueUniqueWork(NOW_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }

    private fun apply(current: BackupSettings) {
        if (!current.enabled || BackupSettings.normalizedUrl(current.serverUrl).isEmpty() || !settings.hasCredential()) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            workManager.cancelUniqueWork(NOW_WORK)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkFor(current))
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(current.chargingOnly)
            .build()
        val request = PeriodicWorkRequestBuilder<BackupWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).setConstraints(constraints).build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** The unmetered network (Wi-Fi) unless the user said mobile data is fine. */
    private fun networkFor(current: BackupSettings) = if (current.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

    companion object {
        const val PERIODIC_WORK = "eikon-backup"
        const val NOW_WORK = "eikon-backup-now"
        private const val PERIOD_MINUTES = 30L
    }
}
