package app.eikon.gallery.data.embedding

import androidx.room.withTransaction
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.db.IndexDao
import app.eikon.gallery.data.db.MediaEmbeddingEntity
import app.eikon.gallery.data.db.SearchHitEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Stores and reads the image embeddings, and the scratch table of the current semantic search's hits. */
@Singleton
class EmbeddingRepository @Inject constructor(
    private val database: EikonDatabase,
    private val dao: IndexDao,
) {
    suspend fun save(mediaId: Long, vector: ByteArray) = dao.upsertEmbedding(MediaEmbeddingEntity(mediaId, EMBEDDING_MODEL_ID, vector))

    suspend fun count(): Int = dao.embeddingCount(EMBEDDING_MODEL_ID)

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
        val nextQueryId = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    }
}
