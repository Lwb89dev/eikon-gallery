package app.eikon.gallery.data.backup

import app.eikon.gallery.domain.MediaItem
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backup's run: order, what counts against a photo and what only ends the run, and that nothing is sent twice. */
class BackupRunnerTest {
    private val queue = FakeQueue((1L..6L).map(::photo))
    private val source = FakeSource()
    private val environment = FakeEnvironment()
    private val target = FakeTarget()

    private fun runner() = BackupRunner(queue, source, environment, target)

    @Test
    fun everyPendingPhotoIsSentNewestFirstAndWrittenDown() = runTest {
        val report = runner().run(60_000)

        assertNull(report.stoppedBy)
        assertEquals(6, report.sent)
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L), target.uploaded)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), queue.done.keys)
    }

    @Test
    fun theServersFingerprintOfEachPhotoIsTheOneItWasSentWith() = runTest {
        runner().run(60_000)
        val file = target.files.getValue(3)
        assertEquals("sha-3", file.sha1)
        assertEquals(300L, file.length)
        assertEquals("f3.jpg", file.name)
    }

    @Test
    fun whatTheServerAlreadyHasIsWrittenDownAndNotSentAgain() = runTest {
        target.has = setOf(2L, 4L)
        val report = runner().run(60_000)

        assertEquals(4, report.sent)
        assertEquals(2, report.alreadyThere)
        assertEquals(listOf(6L, 5L, 3L, 1L), target.uploaded)
        assertTrue(queue.done.keys.containsAll(listOf(2L, 4L)))
    }

    @Test
    fun aFileTheServerRefusesCountsAgainstThatPhotoAndTheRestGoOn() = runTest {
        target.refuse = setOf(4L)
        val report = runner().run(60_000)

        assertEquals(5, report.sent)
        assertEquals(1, report.refused)
        assertEquals(listOf(4L), queue.failed)
        assertNull(report.stoppedBy)
    }

    @Test
    fun aPhotoThatCannotBeReadCountsAgainstItAndTheRestGoOn() = runTest {
        source.unreadable = setOf(5L)
        val report = runner().run(60_000)

        assertEquals(5, report.sent)
        assertEquals(listOf(5L), queue.failed)
    }

    @Test
    fun aServerThatCannotBeReachedEndsTheRunAndCountsAgainstNoPhoto() = runTest {
        target.failWith = { BackupException.Unreachable("no route to host") }
        val report = runner().run(60_000)

        assertEquals(BackupStop.UNREACHABLE, report.stoppedBy)
        assertEquals("no route to host", report.message)
        assertTrue(queue.failed.isEmpty())
        assertTrue(queue.done.isEmpty())
    }

    @Test
    fun aRefusedCredentialEndsTheRunAtOnce() = runTest {
        target.failWith = { BackupException.Unauthorized("401") }
        val report = runner().run(60_000)

        assertEquals(BackupStop.UNAUTHORIZED, report.stoppedBy)
        assertTrue(queue.failed.isEmpty())
    }

    @Test
    fun anUntrustedCertificateAndAWrongAddressEndTheRunToo() = runTest {
        target.failWith = { BackupException.Untrusted("self-signed", "ab:cd") }
        assertEquals(BackupStop.UNTRUSTED, runner().run(60_000).stoppedBy)

        target.failWith = { BackupException.Misconfigured("redirects to http") }
        assertEquals(BackupStop.MISCONFIGURED, runner().run(60_000).stoppedBy)
        assertTrue(queue.failed.isEmpty())
    }

    @Test
    fun whenTheServerDiesHalfWayWhatWasSentIsKeptAndTheRestWaits() = runTest {
        target.failAfter = 2
        val report = runner().run(60_000)

        assertEquals(BackupStop.UNREACHABLE, report.stoppedBy)
        assertEquals(2, report.sent)
        assertEquals(setOf(6L, 5L), queue.done.keys)
        assertTrue(queue.failed.isEmpty())
    }

    @Test
    fun theSlicesTimeRunningOutStopsTheRunBetweenPhotos() = runTest {
        target.onUpload = { environment.now += 10_000 }
        val report = runner().run(budgetMs = 25_000)

        assertEquals(BackupStop.TIME_UP, report.stoppedBy)
        assertTrue("sent ${report.sent}", report.sent in 1..5)
    }

    @Test
    fun turningTheBackupOffStopsTheRun() = runTest {
        target.onUpload = { environment.settings = environment.settings.copy(enabled = false) }
        val report = runner().run(60_000)

        assertEquals(BackupStop.DISABLED, report.stoppedBy)
        assertEquals(1, report.sent)
    }

    @Test
    fun batterySaverStopsABackgroundRunButNotOneTheUserAskedFor() = runTest {
        environment.powerSave = true
        val background = runner().run(60_000)
        assertEquals(BackupStop.POWER_SAVE, background.stoppedBy)
        assertEquals(0, background.sent)

        val asked = runner().run(60_000, manual = true)
        assertNull(asked.stoppedBy)
        assertEquals(6, asked.sent)
    }

    @Test
    fun aPhoneThatIsTooHotStopsTheRun() = runTest {
        environment.thermal = 3
        assertEquals(BackupStop.THERMAL, runner().run(60_000).stoppedBy)
    }

    @Test
    fun aPhotoRefusedInThisRunIsNotOfferedAgainInIt() = runTest {
        // The queue hands a refused photo back (it still has attempts left), as the real one does.
        val persistent = FakeQueue((1L..3L).map(::photo), keepFailedPending = true)
        target.refuse = setOf(2L)

        val report = BackupRunner(persistent, source, environment, target).run(60_000)

        assertNull(report.stoppedBy)
        assertEquals(1, report.refused)
        assertEquals(listOf(2L), persistent.failed)
        assertEquals(listOf(3L, 1L), target.uploaded)
    }

    @Test
    fun aRunOfRefusalsStopsTheRunInsteadOfMarkingEveryPhoto() = runTest {
        val many = FakeQueue((1L..60L).map(::photo))
        target.refuse = (1L..60L).toSet()

        val report = BackupRunner(many, source, environment, target).run(60_000)

        assertEquals(BackupStop.TOO_MANY_REFUSED, report.stoppedBy)
        assertEquals(BackupRunner.MAX_REFUSED_PER_RUN, report.refused)
    }

    @Test
    fun nothingPendingIsAnEmptyRunNotAnError() = runTest {
        val report = BackupRunner(FakeQueue(emptyList()), source, environment, target).run(60_000)
        assertNull(report.stoppedBy)
        assertEquals(0, report.sent)
    }

    @Test
    fun aRunPicksUpWhereTheLastOneStopped() = runTest {
        target.failAfter = 3
        runner().run(60_000)
        target.failAfter = null

        val second = runner().run(60_000)

        assertNull(second.stoppedBy)
        assertEquals(3, second.sent)
        assertEquals(listOf(6L, 5L, 4L, 3L, 2L, 1L), target.uploaded)
    }

    // --- stand-ins ----------------------------------------------------------------------------

    private fun photo(id: Long) = MediaItem(
        id, "f$id.jpg", "image/jpeg", isVideo = false, takenAt = id * 1000, addedAt = 1, modifiedAt = 7, width = 4, height = 3, durationMs = 0,
        sizeBytes = id * 100, relativePath = "DCIM/", bucketName = null, isFavorite = false,
    )

    private class FakeQueue(items: List<MediaItem>, private val keepFailedPending: Boolean = false) : BackupQueue {
        private val all = items.sortedByDescending { it.takenAt }
        val done = LinkedHashMap<Long, BackupFile>()
        val failed = mutableListOf<Long>()

        override suspend fun pending(limit: Int) = all.filter { it.id !in done && (keepFailedPending || it.id !in failed) }.take(limit)
        override suspend fun markDone(item: MediaItem, file: BackupFile) {
            done[item.id] = file
        }

        override suspend fun markFailed(item: MediaItem) {
            failed += item.id
        }
    }

    private class FakeSource : BackupSource {
        var unreadable = setOf<Long>()
        override suspend fun fingerprint(item: MediaItem): Fingerprint {
            if (item.id in unreadable) throw IOException("cannot read")
            return Fingerprint(item.id * 100, "sha-${item.id}")
        }

        override fun open(item: MediaItem): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private class FakeEnvironment : BackupEnvironment {
        var settings = BackupSettings(enabled = true, serverUrl = "https://home.example")
        var thermal = 0
        var powerSave = false
        var now = 1_000L
        override fun settings() = settings
        override fun thermalStatus() = thermal
        override fun isPowerSaveMode() = powerSave
        override fun nowMillis() = now
    }

    private class FakeTarget : BackupTarget {
        val uploaded = mutableListOf<Long>()
        val files = HashMap<Long, BackupFile>()
        var has = setOf<Long>()
        var refuse = setOf<Long>()
        var failWith: (() -> BackupException)? = null
        var failAfter: Int? = null
        var onUpload: () -> Unit = {}

        override suspend fun check() = ServerCheck("fake", null)

        override suspend fun missing(files: List<BackupFile>): List<BackupFile> {
            failWith?.let { if (failAfter == null) throw it() }
            return files.filter { it.mediaId !in has }
        }

        override suspend fun upload(file: BackupFile, open: () -> InputStream): UploadResult {
            failWith?.let { if (failAfter == null) throw it() }
            failAfter?.let { limit -> if (uploaded.size >= limit) throw BackupException.Unreachable("gone") }
            if (file.mediaId in refuse) throw BackupException.Rejected("too big")
            open().close()
            uploaded += file.mediaId
            files[file.mediaId] = file
            onUpload()
            return UploadResult.STORED
        }
    }
}
