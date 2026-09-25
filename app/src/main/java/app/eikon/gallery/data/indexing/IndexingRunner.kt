package app.eikon.gallery.data.indexing

import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.settings.AnalysisSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The queue of photos still to analyse and the way results are recorded. */
interface WorkQueue {
    suspend fun pending(stage: IndexStage, limit: Int): List<MediaEntity>
    suspend fun markDone(mediaId: Long, stage: IndexStage)
    suspend fun markSkipped(mediaId: Long, stage: IndexStage)
    suspend fun markFailed(mediaId: Long, stage: IndexStage)
}

/** What the runner needs to know about the world; separate so tests can drive every situation. */
interface RunnerEnvironment {
    fun analysisSettings(): AnalysisSettings
    fun canReadLocation(): Boolean

    /** `PowerManager.THERMAL_STATUS_*`. */
    fun thermalStatus(): Int

    /** Battery Saver is on: the user asked the phone to spend as little as possible. */
    fun isPowerSaveMode(): Boolean = false
    fun nowMillis(): Long
    suspend fun pause(millis: Long)
}

/** Which analysis steps could not run in the latest run because their model would not load. */
@Singleton
class StageHealth @Inject constructor() {
    private val stages = MutableStateFlow<Set<IndexStage>>(emptySet())
    val unavailable: StateFlow<Set<IndexStage>> = stages.asStateFlow()

    fun markUnavailable(stage: IndexStage) = stages.update { it + stage }

    fun markWorking(stage: IndexStage) = stages.update { it - stage }
}

enum class StopReason {
    /** The time slice for this run is used up; the next scheduled run continues. */
    TIME_UP,

    /** The phone is too warm. */
    THERMAL,

    /** Battery Saver is on and the run was not started by the user; the next scheduled run tries again. */
    POWER_SAVE,

    /** The user paused analysis or switched the step off while it was running. */
    PAUSED,
}

data class RunReport(val processed: Int, val stoppedBy: StopReason?)

/**
 * Works through the pending photos, one stage at a time, newest first, for a limited time slice.
 *
 * Everything that can go wrong for one photo is contained: a failure is recorded against that photo
 * (and retried a few times on later runs) and the run goes on. Between photos it checks whether it
 * should stop: cancelled by the system, out of time, paused by the user, or the phone getting hot.
 * Progress lives in the database, so any stop, crash or reboot only loses the photo in flight.
 */
class IndexingRunner @Inject constructor(
    private val processors: Map<IndexStage, @JvmSuppressWildcards StageProcessor>,
    private val queue: WorkQueue,
    private val environment: RunnerEnvironment,
    private val health: StageHealth = StageHealth(),
) {
    /** [manual] is a run the user asked for ("Analyze now"): it goes ahead even while Battery Saver is on. */
    suspend fun run(budgetMs: Long, manual: Boolean = false): RunReport {
        val slice = Slice(environment.nowMillis() + budgetMs, manual)
        var processed = 0
        try {
            for (stage in enabledStages()) {
                val result = runStage(stage, slice)
                processed += result.processed
                if (result.stoppedBy != null) return RunReport(processed, result.stoppedBy)
            }
            return RunReport(processed, null)
        } finally {
            processors.values.forEach { it.release() }
        }
    }

    private class StageRun(val processed: Int, val stoppedBy: StopReason?)

    /** The limits of one run: when its time is up, and whether the user asked for it. */
    private class Slice(val deadline: Long, val manual: Boolean)

    /** Places first (fast), then the fingerprints for duplicates (fast), then what photos show, then the faces, then the slow text reading. A step is skipped when it is off or cannot work. */
    private fun enabledStages(): List<IndexStage> {
        val settings = environment.analysisSettings()
        if (settings.paused) return emptyList()
        return buildList {
            if (settings.places && environment.canReadLocation()) add(IndexStage.GEO)
            if (settings.duplicates) addAll(listOf(IndexStage.PHASH, IndexStage.FILEHASH))
            if (settings.semantic) add(IndexStage.EMBED)
            if (settings.people) add(IndexStage.FACES)
            if (settings.text) add(IndexStage.OCR)
        }.filter { it in processors }
    }

    private suspend fun runStage(stage: IndexStage, slice: Slice): StageRun {
        val processor = processors.getValue(stage)
        val processed = intArrayOf(0)
        return try {
            runBatches(stage, processor, slice, processed).also { health.markWorking(stage) }
        } catch (_: StageUnavailableException) {
            // Not the photos' fault: leave them untried, skip this step for now and let the others run.
            health.markUnavailable(stage)
            StageRun(processed[0], null)
        }
    }

    private suspend fun runBatches(stage: IndexStage, processor: StageProcessor, slice: Slice, processed: IntArray): StageRun {
        while (true) {
            val batch = queue.pending(stage, BATCH_SIZE)
            if (batch.isEmpty()) return StageRun(processed[0], null)
            for (item in batch) {
                val reason = stopReason(stage, slice)
                if (reason != null) return StageRun(processed[0], reason)
                processOne(processor, stage, item)
                processed[0]++
                coolDownIfWarm()
            }
        }
    }

    private suspend fun stopReason(stage: IndexStage, slice: Slice): StopReason? {
        currentCoroutineContext().ensureActive()
        val settings = environment.analysisSettings()
        return when {
            settings.paused || !isEnabled(stage, settings) -> StopReason.PAUSED
            !slice.manual && environment.isPowerSaveMode() -> StopReason.POWER_SAVE
            ThermalPolicy.decide(environment.thermalStatus()) == ThermalAction.STOP -> StopReason.THERMAL
            environment.nowMillis() >= slice.deadline -> StopReason.TIME_UP
            else -> null
        }
    }

    private fun isEnabled(stage: IndexStage, settings: AnalysisSettings) = when (stage) {
        IndexStage.GEO -> settings.places
        IndexStage.OCR -> settings.text
        IndexStage.EMBED -> settings.semantic
        IndexStage.FACES -> settings.people
        IndexStage.PHASH, IndexStage.FILEHASH -> settings.duplicates
    }

    private suspend fun processOne(processor: StageProcessor, stage: IndexStage, item: MediaEntity) {
        try {
            when (processor.process(item)) {
                StageOutcome.DONE -> queue.markDone(item.id, stage)
                StageOutcome.SKIPPED -> queue.markSkipped(item.id, stage)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: StageUnavailableException) {
            throw unavailable
        } catch (_: Exception) {
            queue.markFailed(item.id, stage)
        }
    }

    private suspend fun coolDownIfWarm() {
        if (ThermalPolicy.decide(environment.thermalStatus()) == ThermalAction.SLOW_DOWN) environment.pause(COOL_DOWN_MS)
    }

    companion object {
        const val BATCH_SIZE = 25
        const val COOL_DOWN_MS = 3_000L
    }
}
