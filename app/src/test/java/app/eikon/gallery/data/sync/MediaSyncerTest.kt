package app.eikon.gallery.data.sync

import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.domain.MediaAccess
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSyncerTest {
    private val source = FakeSource()
    private val index = FakeIndex()
    private val state = FakeState()
    private val syncer = MediaSyncer(source, index, state)

    @Test
    fun firstSyncReadsEverythingAndStoresState() = runTest {
        source.rows = listOf(media(1), media(2), media(3))
        source.generation = 10

        val result = syncer.sync(MediaAccess.FULL)

        assertEquals(SyncResult.Synced(upserted = 3, removed = 0), result)
        assertEquals(setOf(1L, 2L, 3L), index.rows.keys)
        assertEquals(listOf(0L), source.readSince)
        assertEquals(SyncState(generation = 10, version = "v1", accessStamp = "FULL"), state.saved)
    }

    @Test
    fun unchangedGenerationSkipsReading() = runTest {
        source.rows = listOf(media(1))
        source.generation = 10
        syncer.sync(MediaAccess.FULL)
        source.readSince.clear()

        val result = syncer.sync(MediaAccess.FULL)

        assertEquals(SyncResult.UpToDate, result)
        assertTrue(source.readSince.isEmpty())
    }

    @Test
    fun newerGenerationReadsOnlyChangesSinceLastSync() = runTest {
        source.rows = listOf(media(1))
        source.generation = 10
        syncer.sync(MediaAccess.FULL)
        source.generation = 15
        source.readSince.clear()

        syncer.sync(MediaAccess.FULL)

        assertEquals(listOf(10L), source.readSince)
        assertEquals(15L, state.saved.generation)
    }

    @Test
    fun itemsDeletedFromMediaStoreAreRemovedFromIndex() = runTest {
        source.rows = listOf(media(1), media(2), media(3))
        source.generation = 10
        syncer.sync(MediaAccess.FULL)
        source.rows = listOf(media(1), media(3))
        source.generation = 11

        val result = syncer.sync(MediaAccess.FULL)

        assertEquals(SyncResult.Synced(upserted = 0, removed = 1), result)
        assertEquals(setOf(1L, 3L), index.rows.keys)
    }

    @Test
    fun manyDeletionsAreChunkedBelowSqliteVariableLimit() = runTest {
        val total = MediaSyncer.DELETE_CHUNK * 2 + 10
        source.rows = (1L..total).map(::media)
        source.generation = 1
        syncer.sync(MediaAccess.FULL)
        source.rows = emptyList()
        source.generation = 2

        syncer.sync(MediaAccess.FULL)

        assertTrue(index.rows.isEmpty())
        assertTrue(index.deleteCalls.all { it <= MediaSyncer.DELETE_CHUNK })
        assertEquals(total, index.deleteCalls.sum())
    }

    @Test
    fun limitedAccessAlwaysRereadsBecausePickingPhotosDoesNotBumpGenerations() = runTest {
        source.rows = listOf(media(1))
        source.generation = 10
        syncer.sync(MediaAccess.LIMITED)
        source.readSince.clear()

        val result = syncer.sync(MediaAccess.LIMITED)

        assertTrue(result is SyncResult.Synced)
        assertEquals(listOf(0L), source.readSince)
    }

    @Test
    fun accessLevelChangeForcesFullReread() = runTest {
        source.rows = listOf(media(1))
        source.generation = 10
        syncer.sync(MediaAccess.LIMITED)
        source.readSince.clear()

        syncer.sync(MediaAccess.FULL)

        assertEquals(listOf(0L), source.readSince)
    }

    @Test
    fun mediaStoreVersionChangeForcesFullReread() = runTest {
        source.rows = listOf(media(1))
        source.generation = 10
        syncer.sync(MediaAccess.FULL)
        source.version = "v2"
        source.readSince.clear()

        syncer.sync(MediaAccess.FULL)

        assertEquals(listOf(0L), source.readSince)
    }

    @Test
    fun platformWithoutGenerationAlwaysRereads() = runTest {
        source.rows = listOf(media(1))
        source.generation = null
        syncer.sync(MediaAccess.FULL)
        source.readSince.clear()

        syncer.sync(MediaAccess.FULL)

        assertEquals(listOf(0L), source.readSince)
    }

    @Test
    fun forceRereadsEvenWhenNothingChanged() = runTest {
        source.rows = listOf(media(1))
        source.generation = 10
        syncer.sync(MediaAccess.FULL)
        source.readSince.clear()

        syncer.sync(MediaAccess.FULL, force = true)

        assertEquals(listOf(0L), source.readSince)
    }

    @Test
    fun losingAccessWipesTheIndexAndTouchesNothingElse() = runTest {
        source.rows = listOf(media(1), media(2))
        source.generation = 10
        syncer.sync(MediaAccess.FULL)
        source.readSince.clear()

        val result = syncer.sync(MediaAccess.NONE)

        assertEquals(SyncResult.NoAccess, result)
        assertTrue(index.rows.isEmpty())
        assertTrue(source.readSince.isEmpty())
        assertEquals(SyncState(), state.saved)
    }

    @Test
    fun failedQueryDoesNotDeleteIndexedItemsOrAdvanceState() = runTest {
        source.rows = listOf(media(1), media(2))
        source.generation = 10
        syncer.sync(MediaAccess.FULL)
        source.generation = 11
        source.failIdQuery = true

        val outcome = runCatching { syncer.sync(MediaAccess.FULL) }

        assertTrue(outcome.isFailure)
        assertEquals(setOf(1L, 2L), index.rows.keys)
        assertEquals(10L, state.saved.generation)
    }

    @Test
    fun progressReportsRowsDoneOutOfTotal() = runTest {
        source.rows = (1L..5L).map(::media)
        source.generation = 1
        val progress = mutableListOf<Pair<Int, Int>>()

        syncer.sync(MediaAccess.FULL) { done, total -> progress += done to total }

        assertEquals(listOf(5 to 5), progress)
    }

    private fun media(id: Long) = MediaEntity(
        id = id,
        displayName = "IMG_$id.jpg",
        mimeType = "image/jpeg",
        isVideo = false,
        takenAt = id,
        addedAt = id,
        modifiedAt = id,
        width = 4000,
        height = 3000,
        durationMs = 0,
        sizeBytes = 1,
        relativePath = "DCIM/Camera/",
        bucketName = "Camera",
        isFavorite = false,
        isScreenshot = false,
        isScreenRecording = false,
        isPanorama = false,
        isRaw = false,
    )

    private class FakeSource : MediaStoreSource {
        var rows: List<MediaEntity> = emptyList()
        var generation: Long? = 1
        var version = "v1"
        var failIdQuery = false
        val readSince = mutableListOf<Long>()

        override fun currentGeneration() = generation
        override fun currentVersion() = version

        override suspend fun readChanged(
            sinceGeneration: Long,
            batchSize: Int,
            onBatch: suspend (List<MediaEntity>, Int) -> Unit,
        ) {
            readSince += sinceGeneration
            // The fake has no per-row generations, so a "since" read returns nothing new unless
            // this is a full read; tests that need changed rows switch generation and re-add rows.
            val visible = if (sinceGeneration == 0L) rows else emptyList()
            visible.chunked(batchSize).forEach { onBatch(it, visible.size) }
        }

        override suspend fun readAllIds(): List<Long> {
            check(!failIdQuery) { "query failed" }
            return rows.map { it.id }
        }
    }

    private class FakeIndex : MediaIndex {
        val rows = linkedMapOf<Long, MediaEntity>()
        val deleteCalls = mutableListOf<Int>()

        override suspend fun upsertAll(items: List<MediaEntity>) {
            items.forEach { rows[it.id] = it }
        }

        override suspend fun allIds() = rows.keys.toList()

        override suspend fun deleteByIds(ids: List<Long>) {
            deleteCalls += ids.size
            ids.forEach { rows.remove(it) }
        }

        override suspend fun clear() = rows.clear()
    }

    private class FakeState : SyncStateStore {
        var saved = SyncState()
        override suspend fun read() = saved
        override suspend fun write(state: SyncState) {
            saved = state
        }
    }
}
