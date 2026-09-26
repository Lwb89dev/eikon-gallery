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

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun byId(id: Long): MediaEntity?

    /** The rows as they are now, for finding out which ones a sync is about to change. */
    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<MediaEntity>

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

    // The queries below come in two kinds. A list that reads what the analysis finds (a place, a person, a search) must be redone when the analysis stores something; every other list (the library,
    // a folder, an album, a preset...) does not read those tables at all, and redoing it after each photo analysed (its date sections alone read the whole index) kept the database busy for as long
    // as the analysis ran. `LibraryQueryBuilder.readsAnalysis` says which kind a query is; the "Steady" ones watch only the tables that list can change with.

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class, EditRecipeEntity::class, MediaGeoEntity::class, MediaSearchEntity::class, MediaCaptionEntity::class, FaceEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class, EditRecipeEntity::class])
    fun observeCountSteady(query: SupportSQLiteQuery): Flow<Int>

    /** The position of one item in a slice (see [LibraryQueryBuilder.position]); null while it is not in it, so it turns up by itself once the index has it. */
    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class, EditRecipeEntity::class, MediaGeoEntity::class, MediaSearchEntity::class, MediaCaptionEntity::class, FaceEntity::class])
    fun observePosition(query: SupportSQLiteQuery): Flow<Int?>

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class, EditRecipeEntity::class])
    fun observePositionSteady(query: SupportSQLiteQuery): Flow<Int?>

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class, EditRecipeEntity::class, MediaGeoEntity::class, MediaSearchEntity::class, MediaCaptionEntity::class, FaceEntity::class])
    fun observeCover(query: SupportSQLiteQuery): Flow<MediaEntity?>

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class, EditRecipeEntity::class])
    fun observeCoverSteady(query: SupportSQLiteQuery): Flow<MediaEntity?>

    /** SQL comes from LibraryQueryBuilder, which only ever interpolates whitelisted constants. */
    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class, EditRecipeEntity::class, MediaGeoEntity::class, MediaSearchEntity::class, MediaCaptionEntity::class, FaceEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, MediaEntity>

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class, EditRecipeEntity::class])
    fun pagingSourceSteady(query: SupportSQLiteQuery): PagingSource<Int, MediaEntity>

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class, EditRecipeEntity::class, MediaGeoEntity::class, MediaSearchEntity::class, MediaCaptionEntity::class, FaceEntity::class])
    fun observeSectionCounts(query: SupportSQLiteQuery): Flow<List<SectionCountRow>>

    @RawQuery(observedEntities = [MediaEntity::class, AlbumItemEntity::class, HiddenMediaEntity::class, EditRecipeEntity::class])
    fun observeSectionCountsSteady(query: SupportSQLiteQuery): Flow<List<SectionCountRow>>

    /** Emits (0, whatever the query says) each time the analysis stores something a search reads: text, places, faces, captions. */
    @RawQuery(observedEntities = [MediaGeoEntity::class, MediaSearchEntity::class, MediaCaptionEntity::class, FaceEntity::class])
    fun observeAnalysisChanges(query: SupportSQLiteQuery): Flow<Int>
}
