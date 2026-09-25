package app.eikon.gallery.data.sync

import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.domain.MediaAccess
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read side of MediaStore, as far as the index needs it. Implemented over ContentResolver. */
interface MediaStoreSource {
    /** Change counter of the external volume, or null when the platform cannot report one. */
    fun currentGeneration(): Long?

    /** Changes when MediaStore is rebuilt or migrated; all cached rows are then suspect. */
    fun currentVersion(): String

    /**
     * Streams every visible photo/video whose generation is newer than [sinceGeneration] (0 = all),
     * newest first, in batches. [onBatch] also receives the total number of rows of the query.
     */
    suspend fun readChanged(
        sinceGeneration: Long,
        batchSize: Int,
        onBatch: suspend (batch: List<MediaEntity>, totalRows: Int) -> Unit,
    )

    /** Ids of everything currently visible. Must throw, not return empty, if the query failed. */
    suspend fun readAllIds(): List<Long>
}

/** Write side of the Room index, narrowed to what syncing needs. */
interface MediaIndex {
    suspend fun upsertAll(items: List<MediaEntity>)
    suspend fun allIds(): List<Long>
    suspend fun deleteByIds(ids: List<Long>)
    suspend fun clear()
}

data class SyncState(
    val generation: Long = NO_GENERATION,
    val version: String? = null,
    val accessStamp: String? = null,
) {
    companion object {
        const val NO_GENERATION = -1L
    }
}

interface SyncStateStore {
    suspend fun read(): SyncState
    suspend fun write(state: SyncState)
}

sealed interface SyncResult {
    data object NoAccess : SyncResult
    data object UpToDate : SyncResult
    data class Synced(val upserted: Int, val removed: Int) : SyncResult
}

/**
 * Keeps the Room index equal to what MediaStore currently exposes to eikon.
 *
 * - Additions and edits: rows with `_generation_modified` newer than the last synced generation are
 *   upserted (MediaStore's documented change-tracking mechanism).
 * - Deletions: MediaStore cannot list deleted rows, so the set of ids is diffed against the index.
 * - A full re-read is done when incremental tracking cannot be trusted: first run, MediaStore
 *   version change, access level change, "selected photos" access (picking more photos does not bump
 *   any row's generation), or a platform that reports no generation.
 * - Without any access the index is wiped, so revoking the permission also revokes eikon's copy of
 *   the library metadata.
 *
 * The generation is read *before* the rows, so a change racing with the sync is re-read next time
 * (upserts are idempotent).
 */
@Singleton
class MediaSyncer @Inject constructor(
    private val source: MediaStoreSource,
    private val index: MediaIndex,
    private val stateStore: SyncStateStore,
) {
    private val lock = Mutex()

    suspend fun sync(
        access: MediaAccess,
        force: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SyncResult = lock.withLock {
        if (access == MediaAccess.NONE) return@withLock wipe()
        val generation = source.currentGeneration()
        val version = source.currentVersion()
        val stored = stateStore.read()
        val fullRead = force || needsFullRead(access, generation, version, stored)
        if (!fullRead && generation == stored.generation) return@withLock SyncResult.UpToDate

        val since = if (fullRead) 0L else stored.generation
        var done = 0
        source.readChanged(since, BATCH_SIZE) { batch, total ->
            index.upsertAll(batch)
            done += batch.size
            onProgress(done, total)
        }
        val removed = removeVanished()
        stateStore.write(SyncState(generation ?: SyncState.NO_GENERATION, version, access.name))
        SyncResult.Synced(upserted = done, removed = removed)
    }

    private fun needsFullRead(access: MediaAccess, generation: Long?, version: String, stored: SyncState): Boolean =
        access == MediaAccess.LIMITED ||
            generation == null ||
            stored.generation == SyncState.NO_GENERATION ||
            stored.version != version ||
            stored.accessStamp != access.name

    private suspend fun wipe(): SyncResult {
        index.clear()
        stateStore.write(SyncState())
        return SyncResult.NoAccess
    }

    private suspend fun removeVanished(): Int {
        val present = source.readAllIds().toHashSet()
        val stale = index.allIds().filter { it !in present }
        stale.chunked(DELETE_CHUNK).forEach { index.deleteByIds(it) }
        return stale.size
    }

    companion object {
        const val BATCH_SIZE = 1000

        /** Stays below SQLite's bound-variable limit of 999. */
        const val DELETE_CHUNK = 500
    }
}
