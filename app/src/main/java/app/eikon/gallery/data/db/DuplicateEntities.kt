package app.eikon.gallery.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The SHA-256 of a file's bytes, only computed for files that share their size with another file (identical files
 * always do). [modifiedAt] is the file's modification time when it was hashed: if the file has been edited since, the
 * hash is stale and ignored.
 */
@Entity(tableName = "content_hash")
data class ContentHashEntity(
    @PrimaryKey val mediaId: Long,
    val hash: String,
    val modifiedAt: Long,
)

/** A 64-bit fingerprint of what a photo looks like (see PerceptualHash); [modifiedAt] as for [ContentHashEntity]. */
@Entity(tableName = "perceptual_hash")
data class PerceptualHashEntity(
    @PrimaryKey val mediaId: Long,
    val hash: Long,
    val modifiedAt: Long,
)

/** A group of duplicates or similar shots the user said are not, so it is not offered again. [key] identifies the group's members. */
@Entity(tableName = "duplicate_dismissed")
data class DuplicateDismissalEntity(
    @PrimaryKey val key: String,
    val dismissedAt: Long,
)

/**
 * What the user told Memories: a memory to hide, a kind to show less of, a person to show less of, a date to leave out.
 * [key] is `memory:<id>`, `kind:<KIND>`, `person:<id>` or `date:<yyyy-mm-dd>`; [value] counts "show fewer" for a kind.
 * User data, not derived: it survives the media cache being cleared.
 */
@Entity(tableName = "memory_preference")
data class MemoryPreferenceEntity(
    @PrimaryKey val key: String,
    val value: Int,
    val createdAt: Long,
)

/** What is needed to judge one photo or video as a duplicate candidate, with whatever hashes are current. */
class HashCandidate(
    val id: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val takenAt: Long,
    val addedAt: Long,
    val isVideo: Boolean,
    val isFavorite: Boolean,
    val contentHash: String?,
    val perceptual: Long?,
)
