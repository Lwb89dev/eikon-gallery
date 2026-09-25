package app.eikon.gallery.data.indexing

import app.eikon.gallery.data.settings.AnalysisSettings

/** What to ask WorkManager for, given the user's analysis settings. */
sealed interface ScheduleDecision {
    /** Nothing should run: analysis is paused or every step is off. */
    data object Cancel : ScheduleDecision

    /** Run periodically; [requiresCharging] adds the "only while charging" constraint. */
    data class Periodic(val requiresCharging: Boolean) : ScheduleDecision
}

object SchedulePolicy {
    fun decide(settings: AnalysisSettings): ScheduleDecision = when {
        settings.paused || !settings.anyEnabled -> ScheduleDecision.Cancel
        else -> ScheduleDecision.Periodic(requiresCharging = settings.onlyWhileCharging)
    }
}

enum class ThermalAction {
    /** Full speed. */
    RUN,

    /** The device is warm: keep going but leave gaps so it can cool. */
    SLOW_DOWN,

    /** Too hot: stop now and try again on a later run. */
    STOP,
}

/**
 * Reacts to the platform's thermal status (`PowerManager.THERMAL_STATUS_*`: 0 none, 1 light,
 * 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown). Analysis is background work and must
 * never be the reason a phone gets hot.
 */
object ThermalPolicy {
    private const val MODERATE = 2
    private const val SEVERE = 3

    fun decide(thermalStatus: Int): ThermalAction = when {
        thermalStatus >= SEVERE -> ThermalAction.STOP
        thermalStatus >= MODERATE -> ThermalAction.SLOW_DOWN
        else -> ThermalAction.RUN
    }
}
