package app.eikon.gallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** SQL kept as a constant so tests can run exactly this text against a real SQLite. */
object IndexQueries {
    /**
     * Photos still to analyse for a stage, newest first: the recent ones are what the user is most likely to search for.
     * Photos only for now (videos have no stage yet).
     */
    const val PENDING = """
        SELECT m.* FROM media m
        LEFT JOIN index_state s ON s.mediaId = m.id AND s.stage = :stage
        WHERE m.isVideo = 0 AND (s.mediaId IS NULL OR (s.status = 2 AND s.attempts < :maxAttempts))
        ORDER BY m.takenAt DESC, m.id DESC
        LIMIT :limit
        """

    /** [PENDING] restricted to some photos: the ones on screen, which are analysed before the rest of the library. */
    const val PENDING_AMONG = """
        SELECT m.* FROM media m
        LEFT JOIN index_state s ON s.mediaId = m.id AND s.stage = :stage
        WHERE m.isVideo = 0 AND m.id IN (:ids) AND (s.mediaId IS NULL OR (s.status = 2 AND s.attempts < :maxAttempts))
        ORDER BY m.takenAt DESC, m.id DESC
        """
}

@Dao
interface IndexDao {
    @Query(IndexQueries.PENDING)
    suspend fun pending(stage: String, maxAttempts: Int, limit: Int): List<MediaEntity>

    @Query(IndexQueries.PENDING_AMONG)
    suspend fun pendingAmong(stage: String, maxAttempts: Int, ids: List<Long>): List<MediaEntity>

    /** The stored vector of one photo, or null if it has none (or only one from another model). */
    @Query("SELECT vector FROM media_embedding WHERE mediaId = :mediaId AND model = :model")
    suspend fun embedding(mediaId: Long, model: String): ByteArray?

    @Query("SELECT attempts FROM index_state WHERE mediaId = :mediaId AND stage = :stage")
    suspend fun attempts(mediaId: Long, stage: String): Int?

    @Upsert
    suspend fun setState(state: IndexStateEntity)

    @Query("SELECT COUNT(*) FROM media WHERE isVideo = 0")
    fun observePhotoCount(): Flow<Int>

    /** Photos that need no further work for [stage]: done, nothing to extract, or failed too often. */
    @Query(
        """
        SELECT COUNT(*) FROM index_state s JOIN media m ON m.id = s.mediaId
        WHERE s.stage = :stage AND m.isVideo = 0 AND (s.status IN (1, 3) OR (s.status = 2 AND s.attempts >= :maxAttempts))
        """,
    )
    fun observeFinished(stage: String, maxAttempts: Int): Flow<Int>

    @Upsert
    suspend fun upsertGeo(geo: MediaGeoEntity)

    @Query("SELECT * FROM media_geo WHERE mediaId = :mediaId")
    suspend fun geo(mediaId: Long): MediaGeoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(rows: List<MediaSearchEntity>)

    @Query("SELECT rowid FROM media_search WHERE rowid IN (:ids)")
    suspend fun existingSearchRows(ids: List<Long>): List<Long>

    @Query("UPDATE media_search SET filename = :filename WHERE rowid = :id")
    suspend fun setFilename(id: Long, filename: String)

    @Query("UPDATE media_search SET ocr = :text WHERE rowid = :id")
    suspend fun setOcr(id: Long, text: String)

    @Query("SELECT ocr FROM media_search WHERE rowid = :id")
    suspend fun ocr(id: Long): String?

    // --- image embeddings and semantic search hits ---------------------------------------------

    @Upsert
    suspend fun upsertEmbedding(embedding: MediaEmbeddingEntity)

    @Query("SELECT COUNT(*) FROM media_embedding WHERE model = :model")
    suspend fun embeddingCount(model: String): Int

    /** How many vectors there are and the sum of their photo ids: together they change when a vector is added or removed, which is how a cached copy of them knows it is out of date. */
    @Query("SELECT COUNT(*) AS count, COALESCE(SUM(mediaId), 0) AS idSum FROM media_embedding WHERE model = :model")
    suspend fun embeddingStats(model: String): EmbeddingStats

    /** A page of stored vectors after [after], in id order, so the whole set can be read in bounded chunks. */
    @Query("SELECT mediaId, vector FROM media_embedding WHERE model = :model AND mediaId > :after ORDER BY mediaId LIMIT :limit")
    suspend fun embeddingRows(model: String, after: Long, limit: Int): List<EmbeddingRow>

    /** The vectors of some photos, to bring a copy of them up to date without reading them all. */
    @Query("SELECT mediaId, vector FROM media_embedding WHERE model = :model AND mediaId IN (:ids)")
    suspend fun embeddingRowsOf(model: String, ids: List<Long>): List<EmbeddingRow>

    @Query("DELETE FROM search_hit WHERE queryId < :oldest")
    suspend fun clearHitsBefore(oldest: Long)

    @Query("DELETE FROM search_hit")
    suspend fun clearHits()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHits(hits: List<SearchHitEntity>)

    // --- cleanup when media disappears ---------------------------------------------------------

    @Query("DELETE FROM index_state WHERE mediaId IN (:ids)")
    suspend fun deleteStates(ids: List<Long>)

    /** Takes one step off the done list of one photo, so the analysis does it again. */
    @Query("DELETE FROM index_state WHERE mediaId = :mediaId AND stage = :stage")
    suspend fun deleteState(mediaId: Long, stage: String)

    @Query("DELETE FROM media_geo WHERE mediaId IN (:ids)")
    suspend fun deleteGeo(ids: List<Long>)

    @Query("DELETE FROM media_search WHERE rowid IN (:ids)")
    suspend fun deleteSearch(ids: List<Long>)

    @Query("DELETE FROM media_embedding WHERE mediaId IN (:ids)")
    suspend fun deleteEmbeddings(ids: List<Long>)

    @Query("DELETE FROM index_state")
    suspend fun clearStates()

    @Query("DELETE FROM media_geo")
    suspend fun clearGeo()

    @Query("DELETE FROM media_search")
    suspend fun clearSearch()

    @Query("DELETE FROM media_embedding")
    suspend fun clearEmbeddings()
}
