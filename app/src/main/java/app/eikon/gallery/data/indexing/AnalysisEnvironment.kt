package app.eikon.gallery.data.indexing

import android.content.Context
import android.os.PowerManager
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.settings.AnalysisSettings
import app.eikon.gallery.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.delay

/** [RunnerEnvironment] over the real device: settings, permissions, thermal and battery-saver state and the clock. */
class AnalysisEnvironment @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val access: MediaAccessChecker,
    private val clock: Clock,
) : RunnerEnvironment {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    override fun analysisSettings(): AnalysisSettings = settings.state.value?.analysis ?: AnalysisSettings()

    override fun canReadLocation(): Boolean = access.canReadLocation()

    override fun thermalStatus(): Int = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE

    override fun isPowerSaveMode(): Boolean = powerManager?.isPowerSaveMode ?: false

    override fun nowMillis(): Long = clock.nowMillis()

    override suspend fun pause(millis: Long) = delay(millis)
}
