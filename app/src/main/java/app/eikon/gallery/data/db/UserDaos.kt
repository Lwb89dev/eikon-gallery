package app.eikon.gallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** An album row with what a tile needs: visible item count and the newest visible item as cover. */
data class AlbumSummary(
    val id: Long,
    val name: String,
    val position: Int,
    val itemCount: Int,
    val coverId: Long?,
    val coverIsVideo: Boolean?,
    val coverModifiedAt: Long?,
)

/** A device folder (MediaStore bucket) with its visible item count and newest item as cover. */
data class FolderSummary(
    val relativePath: String,
    val name: String?,
    val itemCount: Int,
    val coverId: Long,
    val coverIsVideo: Boolean,
    val coverModifiedAt: Long,
)

@Dao
abstract class AlbumDao {
    @Query(
        """
        SELECT a.id, a.name, a.position,
            (SELECT COUNT(*) FROM album_item i JOIN media m ON m.id = i.mediaId
                WHERE i.albumId = a.id AND m.id NOT IN (SELECT mediaId FROM hidden_media)) AS itemCount,
            (SELECT m.id FROM album_item i JOIN media m ON m.id = i.mediaId
                WHERE i.albumId = a.id AND m.id NOT IN (SELECT mediaId FROM hidden_media)
                ORDER BY m.takenAt DESC, m.id DESC LIMIT 1) AS coverId,
            (SELECT m.isVideo FROM album_item i JOIN media m ON m.id = i.mediaId
                WHERE i.albumId = a.id AND m.id NOT IN (SELECT mediaId FROM hidden_media)
                ORDER BY m.takenAt DESC, m.id DESC LIMIT 1) AS coverIsVideo,
            (SELECT m.modifiedAt FROM album_item i JOIN media m ON m.id = i.mediaId
                WHERE i.albumId = a.id AND m.id NOT IN (SELECT mediaId FROM hidden_media)
                ORDER BY m.takenAt DESC, m.id DESC LIMIT 1) AS coverModifiedAt
        FROM album a ORDER BY a.position, a.id
        """,
    )
    abstract fun observeAlbums(): Flow<List<AlbumSummary>>

    @Query("SELECT * FROM album WHERE id = :id")
    abstract fun observeAlbum(id: Long): Flow<AlbumEntity?>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM album")
    protected abstract suspend fun nextPosition(): Int

    @Insert
    protected abstract suspend fun insert(album: AlbumEntity): Long

    @Transaction
    open suspend fun create(name: String, nowMillis: Long): Long =
        insert(AlbumEntity(name = name, createdAt = nowMillis, position = nextPosition()))

    @Query("UPDATE album SET name = :name WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM album WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addItems(items: List<AlbumItemEntity>)

    @Query("DELETE FROM album_item WHERE albumId = :albumId AND mediaId IN (:mediaIds)")
    abstract suspend fun removeItems(albumId: Long, mediaIds: List<Long>)

    @Query("SELECT id FROM album ORDER BY position, id")
    protected abstract suspend fun orderedIds(): List<Long>

    @Query("UPDATE album SET position = :position WHERE id = :id")
    protected abstract suspend fun setPosition(id: Long, position: Int)

    /** Moves an album one step earlier (-1) or later (+1) and renumbers positions densely. */
    @Transaction
    open suspend fun move(id: Long, delta: Int) {
        val ids = orderedIds().toMutableList()
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        ids.forEachIndexed { index, albumId -> setPosition(albumId, index) }
    }
}

@Dao
interface HiddenDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hide(items: List<HiddenMediaEntity>)

    @Query("DELETE FROM hidden_media WHERE mediaId IN (:ids)")
    suspend fun unhide(ids: List<Long>)
}
