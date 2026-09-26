package app.eikon.gallery.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** SQL kept as a constant so tests can run exactly this text against a real SQLite. */
object DuplicateQueries {
    /**
     * Every visible photo or video that has a current hash of either kind. A hash counts only if it was made from the
     * file as it is now (same modification time), so an edited photo is never matched by what it used to be.
     */
    const val CANDIDATES = """
        SELECT m.id AS id, m.width AS width, m.height AS height, m.sizeBytes AS sizeBytes, m.takenAt AS takenAt,
               m.addedAt AS addedAt, m.isVideo AS isVideo, m.isFavorite AS isFavorite,
               c.hash AS contentHash, p.hash AS perceptual
        FROM media m
        LEFT JOIN content_hash c ON c.mediaId = m.id AND c.modifiedAt = m.modifiedAt
        LEFT JOIN perceptual_hash p ON p.mediaId = m.id AND p.modifiedAt = m.modifiedAt
        WHERE m.id NOT IN (SELECT mediaId FROM hidden_media) AND (c.hash IS NOT NULL OR p.hash IS NOT NULL)
        """

    /** Photos still to fingerprint: none yet, or the file changed since; never ones that failed too often or had nothing to read. */
    const val PENDING_PERCEPTUAL = """
        SELECT m.* FROM media m
        LEFT JOIN perceptual_hash h ON h.mediaId = m.id
        LEFT JOIN index_state s ON s.mediaId = m.id AND s.stage = 'PHASH'
        WHERE m.isVideo = 0
          AND ((s.mediaId IS NULL) OR (s.status = 2 AND s.attempts < :maxAttempts) OR (h.mediaId IS NOT NULL AND h.modifiedAt != m.modifiedAt))
        ORDER BY m.takenAt DESC, m.id DESC
        LIMIT :limit
        """

    /** [PENDING_PERCEPTUAL] restricted to some photos (the ones on screen). */
    const val PENDING_PERCEPTUAL_AMONG = """
        SELECT m.* FROM media m
        LEFT JOIN perceptual_hash h ON h.mediaId = m.id
        LEFT JOIN index_state s ON s.mediaId = m.id AND s.stage = 'PHASH'
        WHERE m.isVideo = 0 AND m.id IN (:ids)
          AND ((s.mediaId IS NULL) OR (s.status = 2 AND s.attempts < :maxAttempts) OR (h.mediaId IS NOT NULL AND h.modifiedAt != m.modifiedAt))
        ORDER BY m.takenAt DESC, m.id DESC
        """

    /**
     * Files to hash byte by byte: only those that have the same size as another file of the same kind, because identical
     * files always do, and reading every file in full would be far too slow.
     */
    const val PENDING_CONTENT = """
        SELECT m.* FROM media m
        LEFT JOIN content_hash h ON h.mediaId = m.id
        LEFT JOIN index_state s ON s.mediaId = m.id AND s.stage = 'FILEHASH'
        WHERE m.sizeBytes > 0
          AND EXISTS (SELECT 1 FROM media t WHERE t.sizeBytes = m.sizeBytes AND t.isVideo = m.isVideo AND t.id != m.id)
          AND ((h.mediaId IS NULL AND (s.mediaId IS NULL OR s.status != 2 OR s.attempts < :maxAttempts)) OR (h.mediaId IS NOT NULL AND h.modifiedAt != m.modifiedAt))
        ORDER BY m.takenAt DESC, m.id DESC
        LIMIT :limit
        """

    /** [PENDING_CONTENT] restricted to some files (the ones on screen). */
    const val PENDING_CONTENT_AMONG = """
        SELECT m.* FROM media m
        LEFT JOIN content_hash h ON h.mediaId = m.id
        LEFT JOIN index_state s ON s.mediaId = m.id AND s.stage = 'FILEHASH'
        WHERE m.sizeBytes > 0 AND m.id IN (:ids)
          AND EXISTS (SELECT 1 FROM media t WHERE t.sizeBytes = m.sizeBytes AND t.isVideo = m.isVideo AND t.id != m.id)
          AND ((h.mediaId IS NULL AND (s.mediaId IS NULL OR s.status != 2 OR s.attempts < :maxAttempts)) OR (h.mediaId IS NOT NULL AND h.modifiedAt != m.modifiedAt))
        ORDER BY m.takenAt DESC, m.id DESC
        """
}

/** A photo's id and when it was taken. */
class IdTime(val id: Long, val takenAt: Long)

@Dao
interface DuplicatesDao {
    /** Visible photos with the time they were taken, for looking for shots of one moment. */
    @Query("SELECT id, takenAt FROM media WHERE isVideo = 0 AND id NOT IN (SELECT mediaId FROM hidden_media)")
    suspend fun photoTimes(): List<IdTime>

    @Upsert
    suspend fun upsertContentHash(hash: ContentHashEntity)

    @Upsert
    suspend fun upsertPerceptualHash(hash: PerceptualHashEntity)

    @Query(DuplicateQueries.CANDIDATES)
    suspend fun candidates(): List<HashCandidate>

    @Query(DuplicateQueries.PENDING_PERCEPTUAL)
    suspend fun pendingPerceptual(maxAttempts: Int, limit: Int): List<MediaEntity>

    @Query(DuplicateQueries.PENDING_CONTENT)
    suspend fun pendingContent(maxAttempts: Int, limit: Int): List<MediaEntity>

    @Query(DuplicateQueries.PENDING_PERCEPTUAL_AMONG)
    suspend fun pendingPerceptualAmong(maxAttempts: Int, ids: List<Long>): List<MediaEntity>

    @Query(DuplicateQueries.PENDING_CONTENT_AMONG)
    suspend fun pendingContentAmong(maxAttempts: Int, ids: List<Long>): List<MediaEntity>

    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun media(ids: List<Long>): List<MediaEntity>

    @Query("SELECT DISTINCT albumId FROM album_item WHERE mediaId IN (:ids)")
    suspend fun albumsOf(ids: List<Long>): List<Long>

    @Query("SELECT `key` FROM duplicate_dismissed")
    suspend fun dismissedKeys(): List<String>

    @Upsert
    suspend fun dismiss(dismissal: DuplicateDismissalEntity)

    @Query("DELETE FROM duplicate_dismissed")
    suspend fun clearDismissals()

    @Query("DELETE FROM content_hash WHERE mediaId IN (:ids)")
    suspend fun deleteContentHashes(ids: List<Long>)

    @Query("DELETE FROM perceptual_hash WHERE mediaId IN (:ids)")
    suspend fun deletePerceptualHashes(ids: List<Long>)

    @Query("DELETE FROM content_hash")
    suspend fun clearContentHashes()

    @Query("DELETE FROM perceptual_hash")
    suspend fun clearPerceptualHashes()
}
