package app.eikon.gallery.data.mediastore

import android.database.Cursor
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import app.eikon.gallery.data.db.MediaEntity

/**
 * Turns rows of the MediaStore `files` table (projection [PROJECTION]) into [MediaEntity]. Column
 * positions are resolved once per query instead of once per row.
 */
class MediaRowReader(cursor: Cursor) {
    private val id = cursor.getColumnIndexOrThrow(MediaColumns._ID)
    private val name = cursor.getColumnIndexOrThrow(MediaColumns.DISPLAY_NAME)
    private val mime = cursor.getColumnIndexOrThrow(MediaColumns.MIME_TYPE)
    private val type = cursor.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
    private val taken = cursor.getColumnIndexOrThrow(MediaColumns.DATE_TAKEN)
    private val added = cursor.getColumnIndexOrThrow(MediaColumns.DATE_ADDED)
    private val modified = cursor.getColumnIndexOrThrow(MediaColumns.DATE_MODIFIED)
    private val width = cursor.getColumnIndexOrThrow(MediaColumns.WIDTH)
    private val height = cursor.getColumnIndexOrThrow(MediaColumns.HEIGHT)
    private val duration = cursor.getColumnIndexOrThrow(MediaColumns.DURATION)
    private val size = cursor.getColumnIndexOrThrow(MediaColumns.SIZE)
    private val path = cursor.getColumnIndexOrThrow(MediaColumns.RELATIVE_PATH)
    private val bucket = cursor.getColumnIndexOrThrow(MediaColumns.BUCKET_DISPLAY_NAME)
    private val favorite = cursor.getColumnIndexOrThrow(MediaColumns.IS_FAVORITE)

    fun read(cursor: Cursor): MediaEntity {
        val isVideo = cursor.getInt(type) == FileColumns.MEDIA_TYPE_VIDEO
        val displayName = cursor.getString(name).orEmpty()
        val mimeType = cursor.getString(mime).orEmpty()
        val relativePath = cursor.getString(path)
        val bucketName = cursor.getString(bucket)
        val widthPx = cursor.getInt(width)
        val heightPx = cursor.getInt(height)
        val categories = MediaClassifier.classify(isVideo, mimeType, displayName, relativePath, bucketName, widthPx, heightPx)
        val addedMs = cursor.getLong(added) * MILLIS_PER_SECOND
        val modifiedMs = cursor.getLong(modified) * MILLIS_PER_SECOND
        return MediaEntity(
            id = cursor.getLong(id),
            displayName = displayName,
            mimeType = mimeType,
            isVideo = isVideo,
            takenAt = captureTime(cursor.getLong(taken), modifiedMs, addedMs),
            addedAt = addedMs,
            modifiedAt = modifiedMs,
            width = widthPx,
            height = heightPx,
            durationMs = cursor.getLong(duration),
            sizeBytes = cursor.getLong(size),
            relativePath = relativePath,
            bucketName = bucketName,
            isFavorite = cursor.getInt(favorite) == 1,
            isScreenshot = categories.isScreenshot,
            isScreenRecording = categories.isScreenRecording,
            isPanorama = categories.isPanorama,
            isRaw = categories.isRaw,
        )
    }

    /** Many files have no capture date; fall back to when they were modified, then added. */
    private fun captureTime(taken: Long, modified: Long, added: Long): Long = when {
        taken > 0 -> taken
        modified > 0 -> modified
        else -> added
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L

        val PROJECTION = arrayOf(
            MediaColumns._ID,
            MediaColumns.DISPLAY_NAME,
            MediaColumns.MIME_TYPE,
            FileColumns.MEDIA_TYPE,
            MediaColumns.DATE_TAKEN,
            MediaColumns.DATE_ADDED,
            MediaColumns.DATE_MODIFIED,
            MediaColumns.WIDTH,
            MediaColumns.HEIGHT,
            MediaColumns.DURATION,
            MediaColumns.SIZE,
            MediaColumns.RELATIVE_PATH,
            MediaColumns.BUCKET_DISPLAY_NAME,
            MediaColumns.IS_FAVORITE,
        )
    }
}
