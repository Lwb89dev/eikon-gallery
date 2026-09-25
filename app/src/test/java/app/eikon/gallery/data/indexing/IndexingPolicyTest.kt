package app.eikon.gallery.data.indexing

import app.eikon.gallery.data.settings.AnalysisSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class IndexingPolicyTest {
    private val on = AnalysisSettings(places = true, text = true)

    @Test
    fun analysisIsOffByDefaultSoNothingIsScheduledOrStored() {
        val defaults = AnalysisSettings()
        assertEquals(false, defaults.places)
        assertEquals(false, defaults.text)
        assertEquals(ScheduleDecision.Cancel, SchedulePolicy.decide(defaults))
    }

    @Test
    fun enabledAnalysisSchedulesAPeriodicRunThatNeedsChargingByDefault() {
        assertEquals(ScheduleDecision.Periodic(requiresCharging = true), SchedulePolicy.decide(on))
    }

    @Test
    fun theChargingConstraintFollowsTheSetting() {
        assertEquals(ScheduleDecision.Periodic(requiresCharging = false), SchedulePolicy.decide(on.copy(onlyWhileCharging = false)))
    }

    @Test
    fun pausedOrFullyDisabledAnalysisCancelsTheSchedule() {
        assertEquals(ScheduleDecision.Cancel, SchedulePolicy.decide(on.copy(paused = true)))
        assertEquals(ScheduleDecision.Cancel, SchedulePolicy.decide(AnalysisSettings(places = false, text = false)))
    }

    @Test
    fun oneEnabledStepIsEnoughToSchedule() {
        assertEquals(ScheduleDecision.Periodic(true), SchedulePolicy.decide(AnalysisSettings(places = true)))
        assertEquals(ScheduleDecision.Periodic(true), SchedulePolicy.decide(AnalysisSettings(text = true)))
    }

    @Test
    fun thermalStatusMapsToRunSlowDownOrStop() {
        assertEquals(ThermalAction.RUN, ThermalPolicy.decide(0))
        assertEquals(ThermalAction.RUN, ThermalPolicy.decide(1))
        assertEquals(ThermalAction.SLOW_DOWN, ThermalPolicy.decide(2))
        assertEquals(ThermalAction.STOP, ThermalPolicy.decide(3))
        assertEquals(ThermalAction.STOP, ThermalPolicy.decide(6))
    }
}
