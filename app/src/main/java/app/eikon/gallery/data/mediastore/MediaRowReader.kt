package app.eikon.gallery.data.mediastore

import android.content.ContentResolver
import android.database.Cursor
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.domain.MotionPhotoDetector
import app.eikon.gallery.domain.mediaContentUri
import java.io.IOException

/**
 * Turns rows of the MediaStore `files` table (projection [PROJECTION]) into [MediaEntity]. Column
 * positions are resolved once per query instead of once per row.
 *
 * Unlike the rest of the row, whether a JPEG is a Motion Photo cannot be told from the cursor: [resolver] opens the file and reads the first
 * [MOTION_HEADER_BYTES] of it (comfortably more than the XMP packet a real photo carries), which [MotionPhotoDetector] then reads for one of
 * the two marker schemas it understands. A file that cannot be opened, or is not JPEG, is not even tried.
 */
class MediaRowReader(cursor: Cursor, private val resolver: ContentResolver) {
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
        val idValue = cursor.getLong(id)
        val sizeBytes = cursor.getLong(size)
        return MediaEntity(
            id = idValue,
            displayName = displayName,
            mimeType = mimeType,
            isVideo = isVideo,
            takenAt = captureTime(cursor.getLong(taken), modifiedMs, addedMs),
            addedAt = addedMs,
            modifiedAt = modifiedMs,
            width = widthPx,
            height = heightPx,
            durationMs = cursor.getLong(duration),
            sizeBytes = sizeBytes,
            relativePath = relativePath,
            bucketName = bucketName,
            isFavorite = cursor.getInt(favorite) == 1,
            isScreenshot = categories.isScreenshot,
            isScreenRecording = categories.isScreenRecording,
            isPanorama = categories.isPanorama,
            isRaw = categories.isRaw,
            motionVideoOffset = motionVideoOffset(idValue, isVideo, mimeType, sizeBytes),
        )
    }

    /** Many files have no capture date; fall back to when they were modified, then added. */
    private fun captureTime(taken: Long, modified: Long, added: Long): Long = when {
        taken > 0 -> taken
        modified > 0 -> modified
        else -> added
    }

    private fun motionVideoOffset(id: Long, isVideo: Boolean, mimeType: String, sizeBytes: Long): Long? {
        if (isVideo || !mimeType.equals("image/jpeg", ignoreCase = true) || sizeBytes <= 0) return null
        val header = try {
            resolver.openInputStream(mediaContentUri(id, isVideo = false))?.use { it.readAtMost(MOTION_HEADER_BYTES) }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } ?: return null
        return MotionPhotoDetector.findVideoOffset(header, sizeBytes)
    }

    /** [java.io.InputStream.read] can return short of what was asked; loops until [limit] bytes are in hand or the stream ends. */
    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var read = 0
        while (read < limit) {
            val n = read(buffer, read, limit - read)
            if (n < 0) break
            read += n
        }
        return if (read == limit) buffer else buffer.copyOf(read)
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L

        /** More than enough for the XMP packet of a real photo (see [MotionPhotoDetector]); read once per JPEG at sync time. */
        private const val MOTION_HEADER_BYTES = 262_144

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
