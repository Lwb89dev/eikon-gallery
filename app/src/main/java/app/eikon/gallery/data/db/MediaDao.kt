package app.eikon.gallery.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** One timeline bucket ("2025-08-14" or "2025-08") and how many items fall in it. */
data class SectionCountRow(val bucket: String, val count: Int)

@Dao
interface MediaDao {
    @Upsert
    suspend fun upsertAll(items: List<MediaEntity>)

    @Query("SELECT id FROM media ORDER BY id")
    suspend fun allIds(): List<Long>

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE media SET isFavorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<Long>, favorite: Boolean)

    @Query("DELETE FROM media")
    suspend fun deleteAll()

    /** Folders that have at least one visible (not hidden) item, newest activity first. */
    @Query(
        """
        SELECT m.relativePath AS relativePath, MAX(m.bucketName) AS name, COUNT(*) AS itemCount,
            (SELECT c.id FROM media c WHERE c.relativePath = m.relativePath
                AND c.id NOT IN (SELECT mediaId FROM hidden_media)
                ORDER BY c.takenAt DESC, c.id DESC LIMIT 1) AS coverId,
            (SELECT c.isVideo FROM media c WHERE c.relativePath = m.relativePath
                AND c.id NOT IN (SELECT mediaId FROM hidden_media)
                ORDER BY c.takenAt DESC, c.id DESC LIMIT 1) AS coverIsVideo,
            (SELECT c.modifiedAt FROM media c WHERE c.relativePath = m.relativePath
                AND c.id NOT IN (SELECT mediaId FROM hidden_media)
                ORDER BY c.takenAt DESC, c.id DESC LIMIT 1) AS coverModifiedAt
        FROM media m
        WHERE m.relativePath IS NOT NULL AND m.id NOT IN (SELECT mediaId FROM hidden_media)
        GROUP BY m.relativePath
        ORDER BY MAX(m.takenAt) DESC
        """,
    )
    fun observeFolders(): Flow<List<FolderSummary>>

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class])
    fun observeCover(query: SupportSQLiteQuery): Flow<MediaEntity?>

    /** SQL comes from LibraryQueryBuilder, which only ever interpolates whitelisted constants. */
    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, MediaEntity>

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class])
    fun observeSectionCounts(query: SupportSQLiteQuery): Flow<List<SectionCountRow>>
}
