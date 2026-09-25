package app.eikon.gallery.data.indexing

import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.settings.AnalysisSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexingRunnerTest {
    private val queue = FakeQueue((1L..6L).map(::media))
    private val environment = FakeEnvironment()
    private val geo = FakeProcessor()
    private val ocr = FakeProcessor()
    private val embed = FakeProcessor()
    private val faces = FakeProcessor()
    private val phash = FakeProcessor()
    private val filehash = FakeProcessor()
    private val health = StageHealth()

    private fun runner() = IndexingRunner(
        mapOf(IndexStage.GEO to geo, IndexStage.OCR to ocr, IndexStage.EMBED to embed, IndexStage.FACES to faces, IndexStage.PHASH to phash, IndexStage.FILEHASH to filehash),
        queue,
        environment,
        health,
    )

    @Test
    fun everyPendingPhotoIsProcessedAndRecorded() = runTest {
        val report = runner().run(budgetMs = 60_000)

        assertEquals(RunReport(processed = 12, stoppedBy = null), report) // 6 photos x 2 stages
        assertEquals(List(6) { IndexingRepositoryStatus.DONE }, (1L..6L).map { queue.status(it, IndexStage.OCR) })
        assertEquals(List(6) { IndexingRepositoryStatus.DONE }, (1L..6L).map { queue.status(it, IndexStage.GEO) })
    }

    @Test
    fun photosAreProcessedInTheOrderTheQueueGivesThem() = runTest {
        runner().run(60_000)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), ocr.seen)
    }

    @Test
    fun placesRunBeforeText() = runTest {
        val order = mutableListOf<IndexStage>()
        geo.onProcess = { order += IndexStage.GEO }
        ocr.onProcess = { order += IndexStage.OCR }
        runner().run(60_000)
        assertEquals(List(6) { IndexStage.GEO } + List(6) { IndexStage.OCR }, order)
    }

    @Test
    fun placesAreSkippedWithoutTheLocationPermission() = runTest {
        environment.locationAllowed = false
        runner().run(60_000)
        assertTrue(geo.seen.isEmpty())
        assertEquals(6, ocr.seen.size)
        assertNull(queue.status(1, IndexStage.GEO)) // untouched, not wrongly marked as having no location
    }

    @Test
    fun whatPhotosShowRunsBetweenPlacesAndText() = runTest {
        environment.settings = ON.copy(semantic = true)
        val order = mutableListOf<IndexStage>()
        geo.onProcess = { order += IndexStage.GEO }
        embed.onProcess = { order += IndexStage.EMBED }
        ocr.onProcess = { order += IndexStage.OCR }

        runner().run(60_000)

        assertEquals(List(6) { IndexStage.GEO } + List(6) { IndexStage.EMBED } + List(6) { IndexStage.OCR }, order)
        assertEquals(6, (1L..6L).count { queue.status(it, IndexStage.EMBED) == IndexingRepositoryStatus.DONE })
    }

    @Test
    fun peopleRunAfterWhatPhotosShowAndBeforeText() = runTest {
        environment.settings = ON.copy(semantic = true, people = true)
        val order = mutableListOf<IndexStage>()
        embed.onProcess = { order += IndexStage.EMBED }
        faces.onProcess = { order += IndexStage.FACES }
        ocr.onProcess = { order += IndexStage.OCR }

        runner().run(60_000)

        assertEquals(List(6) { IndexStage.EMBED } + List(6) { IndexStage.FACES } + List(6) { IndexStage.OCR }, order)
    }

    @Test
    fun fingerprintsForDuplicatesRunAfterPlacesAndBeforeTheHeavierSteps() = runTest {
        environment.settings = ON.copy(semantic = true, duplicates = true)
        val order = mutableListOf<IndexStage>()
        geo.onProcess = { order += IndexStage.GEO }
        phash.onProcess = { order += IndexStage.PHASH }
        filehash.onProcess = { order += IndexStage.FILEHASH }
        embed.onProcess = { order += IndexStage.EMBED }

        runner().run(60_000)

        assertEquals(List(6) { IndexStage.GEO } + List(6) { IndexStage.PHASH } + List(6) { IndexStage.FILEHASH } + List(6) { IndexStage.EMBED }, order)
    }

    @Test
    fun findingPeopleAndDuplicatesIsOffUnlessTheUserTurnedItOn() = runTest {
        runner().run(60_000)
        assertTrue(faces.seen.isEmpty() && phash.seen.isEmpty() && filehash.seen.isEmpty())
    }

    @Test
    fun whatPhotosShowIsOffUnlessTheUserTurnedItOn() = runTest {
        runner().run(60_000)
        assertTrue(embed.seen.isEmpty())
    }

    @Test
    fun aModelThatCannotLoadStopsItsStepWithoutBlamingAnyPhotoAndOtherStepsStillRun() = runTest {
        environment.settings = ON.copy(semantic = true)
        embed.unavailable = true

        val report = runner().run(60_000)

        assertNull(report.stoppedBy)
        assertEquals(setOf(IndexStage.EMBED), health.unavailable.value)
        assertTrue((1L..6L).all { queue.status(it, IndexStage.EMBED) == null }) // nothing failed, nothing skipped
        assertEquals(6, ocr.seen.size)

        embed.unavailable = false // the next run, with the model loadable again, picks the photos up
        runner().run(60_000)
        assertTrue(health.unavailable.value.isEmpty())
        assertEquals(6, (1L..6L).count { queue.status(it, IndexStage.EMBED) == IndexingRepositoryStatus.DONE })
    }

    @Test
    fun aStepThatIsSwitchedOffDoesNotRun() = runTest {
        environment.settings = ON.copy(text = false)
        runner().run(60_000)
        assertTrue(ocr.seen.isEmpty())
        assertEquals(6, geo.seen.size)
    }

    @Test
    fun nothingRunsWhenPausedOrWhenEverythingIsOff() = runTest {
        environment.settings = ON.copy(paused = true)
        assertEquals(RunReport(0, null), runner().run(60_000))
        environment.settings = AnalysisSettings(places = false, text = false)
        assertEquals(RunReport(0, null), runner().run(60_000))
        assertTrue(geo.seen.isEmpty() && ocr.seen.isEmpty())
    }

    @Test
    fun aPhotoWithNothingToExtractIsSkippedAndNotVisitedAgain() = runTest {
        ocr.outcomes[2L] = StageOutcome.SKIPPED
        runner().run(60_000)
        assertEquals(IndexingRepositoryStatus.SKIPPED, queue.status(2, IndexStage.OCR))
        ocr.seen.clear()
        runner().run(60_000)
        assertTrue(ocr.seen.isEmpty()) // everything is finished, including the skipped one
    }

    @Test
    fun aFailingPhotoIsRetriedAFewTimesThenLeftAloneWhileOthersContinue() = runTest {
        ocr.failing += 3L
        runner().run(60_000)

        assertEquals(IndexingRepositoryStatus.FAILED, queue.status(3, IndexStage.OCR))
        assertEquals(IndexingRepository.MAX_ATTEMPTS, queue.attempts(3, IndexStage.OCR))
        assertEquals(IndexingRepository.MAX_ATTEMPTS, ocr.seen.count { it == 3L })
        assertEquals(IndexingRepositoryStatus.DONE, queue.status(6, IndexStage.OCR)) // the rest was not blocked
    }

    @Test
    fun cancellationStopsTheRunAndIsNotCountedAsAFailure() = runTest {
        ocr.cancelAt = 2L
        val outcome = runCatching { runner().run(60_000) }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertNull(queue.status(2, IndexStage.OCR))
        assertEquals(IndexingRepositoryStatus.DONE, queue.status(1, IndexStage.OCR))
    }

    @Test
    fun pausingWhileRunningStopsAfterThePhotoInFlight() = runTest {
        ocr.onProcess = { if (it == 2L) environment.settings = ON.copy(paused = true) }
        val report = runner().run(60_000)
        assertEquals(StopReason.PAUSED, report.stoppedBy)
        assertEquals(IndexingRepositoryStatus.DONE, queue.status(2, IndexStage.OCR))
        assertNull(queue.status(3, IndexStage.OCR))
    }

    @Test
    fun theTimeBudgetEndsTheRunAndTheNextRunContinues() = runTest {
        ocr.onProcess = { environment.now += 1_000 } // each photo "takes" one second
        val first = runner().run(budgetMs = 2_500)
        assertEquals(StopReason.TIME_UP, first.stoppedBy)
        val doneAfterFirst = ocr.seen.toList()

        ocr.onProcess = {}
        val second = runner().run(budgetMs = 60_000)
        assertNull(second.stoppedBy)
        assertEquals((1L..6L).toList(), (doneAfterFirst + ocr.seen.drop(doneAfterFirst.size)).distinct())
        assertEquals(6, (1L..6L).count { queue.status(it, IndexStage.OCR) == IndexingRepositoryStatus.DONE })
    }

    @Test
    fun aTooHotPhoneStopsTheRun() = runTest {
        environment.thermal = 3 // severe
        val report = runner().run(60_000)
        assertEquals(StopReason.THERMAL, report.stoppedBy)
        assertEquals(0, report.processed)
    }

    @Test
    fun batterySaverStopsABackgroundRunBeforeItTouchesAnyPhoto() = runTest {
        environment.powerSave = true
        val report = runner().run(60_000)
        assertEquals(StopReason.POWER_SAVE, report.stoppedBy)
        assertEquals(0, report.processed)
    }

    @Test
    fun batterySaverDoesNotCountAgainstThePhotosAndTheNextRunContinues() = runTest {
        environment.powerSave = true
        runner().run(60_000)
        assertEquals(0, (1L..6L).count { queue.status(it, IndexStage.OCR) != null })

        environment.powerSave = false
        val later = runner().run(60_000)
        assertNull(later.stoppedBy)
        assertEquals(6, (1L..6L).count { queue.status(it, IndexStage.OCR) == IndexingRepositoryStatus.DONE })
    }

    @Test
    fun aRunTheUserAskedForGoesAheadInBatterySaver() = runTest {
        environment.powerSave = true
        val report = runner().run(60_000, manual = true)
        assertNull(report.stoppedBy)
        assertEquals(12, report.processed)
    }

    @Test
    fun batterySaverThatStartsMidRunStopsItAtTheNextPhoto() = runTest {
        ocr.onProcess = { id -> if (id == 2L) environment.powerSave = true }
        val report = runner().run(60_000)
        assertEquals(StopReason.POWER_SAVE, report.stoppedBy)
        assertTrue("stopped after ${report.processed}", report.processed in 1..11)
    }

    @Test
    fun aWarmPhoneSlowsDownInsteadOfStopping() = runTest {
        environment.thermal = 2 // moderate
        val report = runner().run(60_000)
        assertNull(report.stoppedBy)
        assertEquals(12, report.processed)
        assertEquals(12, environment.pauses.size)
        assertTrue(environment.pauses.all { it == IndexingRunner.COOL_DOWN_MS })
    }

    @Test
    fun processorsAreReleasedWhenTheRunEndsEvenOnFailure() = runTest {
        runner().run(60_000)
        assertTrue(geo.released && ocr.released)

        geo.released = false
        ocr.released = false
        ocr.cancelAt = 1L
        runCatching { runner().run(60_000) }
        assertTrue(geo.released && ocr.released)
    }

    @Test
    fun anEmptyQueueIsAQuickNoOp() = runTest {
        val empty = IndexingRunner(mapOf(IndexStage.OCR to ocr), FakeQueue(emptyList()), environment)
        assertEquals(RunReport(0, null), empty.run(60_000))
        assertFalse(ocr.released.not())
    }

    // --- fakes ---------------------------------------------------------------------------------

    private object IndexingRepositoryStatus {
        const val DONE = 1
        const val FAILED = 2
        const val SKIPPED = 3
    }

    private class FakeQueue(private val items: List<MediaEntity>) : WorkQueue {
        private data class State(var status: Int, var attempts: Int)
        private val states = HashMap<Pair<Long, IndexStage>, State>()

        fun status(id: Long, stage: IndexStage): Int? = states[id to stage]?.status
        fun attempts(id: Long, stage: IndexStage): Int? = states[id to stage]?.attempts

        override suspend fun pending(stage: IndexStage, limit: Int): List<MediaEntity> = items.filter {
            val state = states[it.id to stage]
            state == null || (state.status == IndexingRepositoryStatus.FAILED && state.attempts < IndexingRepository.MAX_ATTEMPTS)
        }.take(limit)

        override suspend fun markDone(mediaId: Long, stage: IndexStage) {
            states[mediaId to stage] = State(IndexingRepositoryStatus.DONE, 0)
        }

        override suspend fun markSkipped(mediaId: Long, stage: IndexStage) {
            states[mediaId to stage] = State(IndexingRepositoryStatus.SKIPPED, 0)
        }

        override suspend fun markFailed(mediaId: Long, stage: IndexStage) {
            val previous = states[mediaId to stage]?.attempts ?: 0
            states[mediaId to stage] = State(IndexingRepositoryStatus.FAILED, previous + 1)
        }
    }

    private class FakeEnvironment : RunnerEnvironment {
        private val ON = AnalysisSettings(places = true, text = true)
        var settings = ON
        var locationAllowed = true
        var thermal = 0
        var powerSave = false
        var now = 1_000L
        val pauses = mutableListOf<Long>()

        override fun analysisSettings() = settings
        override fun canReadLocation() = locationAllowed
        override fun thermalStatus() = thermal
        override fun isPowerSaveMode() = powerSave
        override fun nowMillis() = now
        override suspend fun pause(millis: Long) {
            pauses += millis
        }
    }

    private class FakeProcessor : StageProcessor {
        val seen = mutableListOf<Long>()
        val outcomes = HashMap<Long, StageOutcome>()
        val failing = mutableSetOf<Long>()
        var cancelAt: Long? = null
        var onProcess: (Long) -> Unit = {}
        var released = false
        var unavailable = false

        override suspend fun process(item: MediaEntity): StageOutcome {
            if (unavailable) throw StageUnavailableException("model missing")
            seen += item.id
            onProcess(item.id)
            if (item.id == cancelAt) throw CancellationException("stop")
            if (item.id in failing) error("cannot read ${item.id}")
            return outcomes[item.id] ?: StageOutcome.DONE
        }

        override fun release() {
            released = true
        }
    }

    private companion object {
        val ON = AnalysisSettings(places = true, text = true)

        fun media(id: Long) = MediaEntity(
            id, "f$id.jpg", "image/jpeg", false, id, id, id, 1, 1, 0, 1, null, null, false, false, false, false, false,
        )
    }
}
