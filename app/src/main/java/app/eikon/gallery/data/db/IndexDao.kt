package app.eikon.gallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface IndexDao {
    /**
     * Photos still to analyse for [stage], newest first: the recent ones are what the user is most
     * likely to search for. Photos only for now (videos have no stage yet).
     */
    @Query(
        """
        SELECT m.* FROM media m
        LEFT JOIN index_state s ON s.mediaId = m.id AND s.stage = :stage
        WHERE m.isVideo = 0 AND (s.mediaId IS NULL OR (s.status = 2 AND s.attempts < :maxAttempts))
        ORDER BY m.takenAt DESC, m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun pending(stage: String, maxAttempts: Int, limit: Int): List<MediaEntity>

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

    /** A page of stored vectors after [after], in id order, so the whole set can be read in bounded chunks. */
    @Query("SELECT mediaId, vector FROM media_embedding WHERE model = :model AND mediaId > :after ORDER BY mediaId LIMIT :limit")
    suspend fun embeddingRows(model: String, after: Long, limit: Int): List<EmbeddingRow>

    @Query("DELETE FROM search_hit WHERE queryId < :oldest")
    suspend fun clearHitsBefore(oldest: Long)

    @Query("DELETE FROM search_hit")
    suspend fun clearHits()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHits(hits: List<SearchHitEntity>)

    // --- cleanup when media disappears ---------------------------------------------------------

    @Query("DELETE FROM index_state WHERE mediaId IN (:ids)")
    suspend fun deleteStates(ids: List<Long>)

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
