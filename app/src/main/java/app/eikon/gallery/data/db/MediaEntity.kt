package app.eikon.gallery.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.eikon.gallery.domain.MediaItem

/**
 * Index row for one MediaStore item. This table is a rebuildable cache of MediaStore metadata:
 * the files stay where Android keeps them, eikon never stores a copy of the pixels.
 *
 * The category flags are computed once at sync time (see MediaClassifier) so filters are plain
 * column comparisons.
 *
 * The date indexes serve the whole library in date order. A device folder is served by the two indexes that start with `relativePath`:
 * without them a small folder in a big library would read the whole date index and look up every row to find its few photos. The index on
 * `sizeBytes` is for finding files of the same size (candidates for identical copies) without comparing every file with every other.
 */
@Entity(
    tableName = "media",
    indices = [Index("takenAt"), Index("addedAt"), Index("relativePath", "takenAt"), Index("relativePath", "addedAt"), Index("sizeBytes", "isVideo")],
)
data class MediaEntity(
    /** MediaStore `_id`; treated as unique across the merged external volume. */
    @PrimaryKey val id: Long,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    val takenAt: Long,
    val addedAt: Long,
    val modifiedAt: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val relativePath: String?,
    val bucketName: String?,
    val isFavorite: Boolean,
    val isScreenshot: Boolean,
    val isScreenRecording: Boolean,
    val isPanorama: Boolean,
    val isRaw: Boolean,
)

fun MediaEntity.toDomain(): MediaItem = MediaItem(
    id = id,
    displayName = displayName,
    mimeType = mimeType,
    isVideo = isVideo,
    takenAt = takenAt,
    addedAt = addedAt,
    modifiedAt = modifiedAt,
    width = width,
    height = height,
    durationMs = durationMs,
    sizeBytes = sizeBytes,
    relativePath = relativePath,
    bucketName = bucketName,
    isFavorite = isFavorite,
)
