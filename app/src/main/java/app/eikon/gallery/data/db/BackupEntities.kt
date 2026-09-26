package app.eikon.gallery.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * What has been done for one photo in the backup to the server the user set up: sent (or found already there) or refused. Bookkeeping only, about what was sent where;
 * cleared when the destination changes (what was sent to one server says nothing about another) and when photo access is revoked.
 */
@Entity(tableName = "backup_item")
data class BackupItemEntity(
    @PrimaryKey val mediaId: Long,
    /** [STATUS_DONE] or [STATUS_FAILED]. */
    val status: Int,
    /** How many times in a row the server refused this file (only for [STATUS_FAILED]). */
    val attempts: Int,
    /** The photo's modification time when it was sent: if the file changes, it is sent again. */
    val modifiedAt: Long,
    val sizeBytes: Long,
    /** SHA-1 of what was sent, in hex. */
    val checksum: String?,
    val updatedAt: Long,
) {
    companion object {
        const val STATUS_DONE = 0
        const val STATUS_FAILED = 1
    }
}

/** How far the backup has got, for the settings screen. */
data class BackupCounts(val total: Int, val done: Int, val failed: Int)

/** The SQL of the backup, on its own so a test can run it on a real SQLite. Flags are 0 or 1. */
object BackupQueries {
    private const val ELIGIBLE = "(:includeVideos = 1 OR m.isVideo = 0) AND (:includeHidden = 1 OR m.id NOT IN (SELECT mediaId FROM hidden_media))"

    /** Photos not sent yet, or changed since, or refused fewer than [:maxAttempts] times; newest first, because those are the ones most worth having safe. */
    const val PENDING = "SELECT m.* FROM media m LEFT JOIN backup_item b ON b.mediaId = m.id " +
        "WHERE (b.mediaId IS NULL OR b.modifiedAt != m.modifiedAt OR (b.status = 1 AND b.attempts < :maxAttempts)) AND $ELIGIBLE " +
        "ORDER BY m.takenAt DESC, m.id DESC LIMIT :limit"

    const val TOTAL = "SELECT COUNT(*) FROM media m WHERE $ELIGIBLE"

    const val DONE = "SELECT COUNT(*) FROM media m JOIN backup_item b ON b.mediaId = m.id AND b.status = 0 AND b.modifiedAt = m.modifiedAt WHERE $ELIGIBLE"

    const val FAILED = "SELECT COUNT(*) FROM media m JOIN backup_item b ON b.mediaId = m.id AND b.status = 1 AND b.modifiedAt = m.modifiedAt WHERE $ELIGIBLE"
}

@Dao
interface BackupDao {
    @Query(BackupQueries.PENDING)
    suspend fun pending(includeVideos: Boolean, includeHidden: Boolean, maxAttempts: Int, limit: Int): List<MediaEntity>

    @Query(BackupQueries.TOTAL)
    fun observeTotal(includeVideos: Boolean, includeHidden: Boolean): Flow<Int>

    @Query(BackupQueries.DONE)
    fun observeDone(includeVideos: Boolean, includeHidden: Boolean): Flow<Int>

    @Query(BackupQueries.FAILED)
    fun observeFailed(includeVideos: Boolean, includeHidden: Boolean): Flow<Int>

    @Query("SELECT * FROM backup_item WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): BackupItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: BackupItemEntity)

    /** Gives the photos that were refused another go (they are sent again at the next run). */
    @Query("DELETE FROM backup_item WHERE status = 1")
    suspend fun forgetFailures()

    @Query("DELETE FROM backup_item")
    suspend fun clear()
}
