package app.eikon.gallery.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.BackupDao
import app.eikon.gallery.data.db.BackupItemEntity
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.domain.MediaItem
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** How the queue writes down what happened to a photo, and the flags it passes to the database's list of what is still to send. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomBackupQueueTest {
    private lateinit var directory: File
    private val dao = FakeDao()

    @Before
    fun makeDirectory() {
        directory = Files.createTempDirectory("backup-queue-test").toFile()
    }

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    private fun TestScope.queue(): RoomBackupQueue {
        val settings = BackupSettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "s.preferences_pb") }, NoSecrets, dao)
        return RoomBackupQueue(dao, settings, Clock { 5_000L })
    }

    private fun photo(modifiedAt: Long = 7) = MediaItem(
        1, "a.jpg", "image/jpeg", false, takenAt = 1, addedAt = 1, modifiedAt = modifiedAt, width = 1, height = 1, durationMs = 0,
        sizeBytes = 123, relativePath = null, bucketName = null, isFavorite = false,
    )

    private fun file(modifiedAt: Long = 7) = BackupFile(1, "a.jpg", "image/jpeg", false, 1, modifiedAt, false, 120, "abc123")

    @Test
    fun aPhotoSentIsWrittenDownWithTheVersionThatWasSent() = runTest(UnconfinedTestDispatcher()) {
        queue().markDone(photo(modifiedAt = 9), file(modifiedAt = 9))

        val row = dao.rows.getValue(1)
        assertEquals(BackupItemEntity.STATUS_DONE, row.status)
        assertEquals(9L, row.modifiedAt)
        assertEquals(120L, row.sizeBytes)
        assertEquals("abc123", row.checksum)
        assertEquals(5_000L, row.updatedAt)
    }

    @Test
    fun eachRefusalOfTheSameVersionCountsOneMore() = runTest(UnconfinedTestDispatcher()) {
        val queue = queue()

        queue.markFailed(photo())
        assertEquals(1, dao.rows.getValue(1).attempts)
        queue.markFailed(photo())
        queue.markFailed(photo())

        assertEquals(3, dao.rows.getValue(1).attempts)
        assertEquals(BackupItemEntity.STATUS_FAILED, dao.rows.getValue(1).status)
    }

    @Test
    fun aFileThatChangedStartsFromOneAgain() = runTest(UnconfinedTestDispatcher()) {
        val queue = queue()
        repeat(3) { queue.markFailed(photo(modifiedAt = 7)) }

        queue.markFailed(photo(modifiedAt = 8))

        assertEquals(1, dao.rows.getValue(1).attempts)
        assertEquals(8L, dao.rows.getValue(1).modifiedAt)
    }

    @Test
    fun aRefusalAfterASuccessStartsAtOneNotAtTheOldCount() = runTest(UnconfinedTestDispatcher()) {
        val queue = queue()
        queue.markDone(photo(), file())

        queue.markFailed(photo(modifiedAt = 8))

        assertEquals(1, dao.rows.getValue(1).attempts)
    }

    @Test
    fun theListOfWhatIsStillToSendUsesTheUsersOptionsAndTheAttemptLimit() = runTest(UnconfinedTestDispatcher()) {
        val queue = queue()

        queue.pending(20)

        assertEquals(Asked(includeVideos = true, includeHidden = false, maxAttempts = RoomBackupQueue.MAX_ATTEMPTS, limit = 20), dao.asked)
    }

    private data class Asked(val includeVideos: Boolean, val includeHidden: Boolean, val maxAttempts: Int, val limit: Int)

    private object NoSecrets : SecretStore {
        override fun get(name: String): String? = null
        override fun put(name: String, value: String) = Unit
        override fun remove(name: String) = Unit
    }

    private class FakeDao : BackupDao {
        val rows = HashMap<Long, BackupItemEntity>()
        var asked: Asked? = null

        override suspend fun pending(includeVideos: Boolean, includeHidden: Boolean, maxAttempts: Int, limit: Int): List<MediaEntity> {
            asked = Asked(includeVideos, includeHidden, maxAttempts, limit)
            return emptyList()
        }

        override fun observeTotal(includeVideos: Boolean, includeHidden: Boolean): Flow<Int> = flowOf(0)
        override fun observeDone(includeVideos: Boolean, includeHidden: Boolean): Flow<Int> = flowOf(0)
        override fun observeFailed(includeVideos: Boolean, includeHidden: Boolean): Flow<Int> = flowOf(0)
        override suspend fun get(mediaId: Long) = rows[mediaId]
        override suspend fun put(item: BackupItemEntity) {
            rows[item.mediaId] = item
        }

        override suspend fun forgetFailures() {
            rows.values.removeAll { it.status == BackupItemEntity.STATUS_FAILED }
        }

        override suspend fun clear() = rows.clear()
    }
}
