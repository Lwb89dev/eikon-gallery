package app.eikon.gallery.data.embedding

import androidx.room.withTransaction
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.db.IndexDao
import app.eikon.gallery.data.db.MediaEmbeddingEntity
import app.eikon.gallery.data.db.SearchHitEntity
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stores and reads the image embeddings, and the scratch table of the current semantic search's hits. */
@Singleton
class EmbeddingRepository @Inject constructor(
    private val database: EikonDatabase,
    private val dao: IndexDao,
) {
    /** Counts vectors written by this process, so a photo whose vector was replaced (its file was edited) makes a cached copy out of date although the number of vectors is the same. */
    private val saves = AtomicLong()

    private val matrixLock = Mutex()

    // Soft: 100,000 photos are about 50 MB, which is worth keeping while the user searches but not worth an out-of-memory error; the system may take it back.
    private var cached: SoftReference<Pair<Version, EmbeddingMatrix>>? = null

    private data class Version(val count: Int, val idSum: Long, val saves: Long)

    suspend fun save(mediaId: Long, vector: ByteArray) {
        dao.upsertEmbedding(MediaEmbeddingEntity(mediaId, EMBEDDING_MODEL_ID, vector))
        saves.incrementAndGet()
    }

    suspend fun count(): Int = dao.embeddingCount(EMBEDDING_MODEL_ID)

    /** Every vector of the current model, from memory if nothing has been added, replaced or removed since it was read, and shared by whoever asks. */
    suspend fun matrix(): EmbeddingMatrix = matrixLock.withLock {
        val stats = dao.embeddingStats(EMBEDDING_MODEL_ID)
        val version = Version(stats.count, stats.idSum, saves.get())
        cached?.get()?.takeIf { it.first == version }?.let { return it.second }
        loadMatrix().also { cached = SoftReference(version to it) }
    }

    /** All stored vectors of the current model, read in chunks so no single query holds the whole set. */
    suspend fun loadMatrix(): EmbeddingMatrix {
        val builder = EmbeddingMatrix.Builder(count())
        var after = Long.MIN_VALUE
        while (true) {
            val rows = dao.embeddingRows(EMBEDDING_MODEL_ID, after, CHUNK)
            if (rows.isEmpty()) return builder.build()
            rows.forEach { builder.add(it.mediaId, it.vector) }
            after = rows.last().mediaId
        }
    }

    /**
     * Stores [hits] under a new query id, which is returned, and forgets all but the most recent queries. The list
     * query then selects the photos of that id.
     */
    suspend fun storeHits(hits: List<ScoredMedia>): Long {
        val queryId = nextQueryId.incrementAndGet()
        database.withTransaction {
            dao.clearHitsBefore(queryId - KEPT_QUERIES)
            hits.chunked(CHUNK).forEach { chunk -> dao.insertHits(chunk.map { SearchHitEntity(queryId, it.mediaId, it.score) }) }
        }
        return queryId
    }

    private companion object {
        const val CHUNK = 500
        const val KEPT_QUERIES = 8

        /** Starts at the clock so ids of a new process never collide with rows left by an earlier one. */
        val nextQueryId = AtomicLong(System.currentTimeMillis())
    }
}
