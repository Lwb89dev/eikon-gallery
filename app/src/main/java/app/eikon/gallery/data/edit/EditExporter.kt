package app.eikon.gallery.data.edit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.EditRenderer
import app.eikon.gallery.domain.edit.GeometryMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A copy saved to the library: where it is, and whether the original's metadata (date, camera, and location if allowed) went with it. */
class SavedCopy(val uri: Uri, val keptMetadata: Boolean)

/**
 * "Save a copy": draws the edit at full resolution and stores it as a **new** JPEG next to the original, which is never touched. This
 * is the only way an edit becomes a file, and it never replaces anything: if the original should go, the user deletes it
 * separately, through Android's own confirmation.
 *
 * The photo is decoded once (reduced to at most [MAX_PIXELS] if it is bigger) and the result is drawn in bands straight into a bitmap, so
 * neither holds a 50-megapixel picture as a Java array. The metadata of the original is copied so the copy keeps its place in the timeline
 * and its camera details; the orientation is reset because the pixels are already turned. Location is copied only if the app holds the
 * "read photo locations" permission (otherwise Android hides it from us, and the copy has none).
 */
@Singleton
class EditExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loader: EditImageLoader,
    private val access: MediaAccessChecker,
) {
    suspend fun saveCopy(item: MediaItem, recipe: EditRecipe, onProgress: (Float) -> Unit = {}): SavedCopy = withContext(Dispatchers.Default) {
        val bitmap = draw(item, recipe, onProgress)
        try {
            store(item, bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Draws the edit into [file] as a JPEG **without any metadata** (no location, no camera details: what is shared carries only the picture).
     * Nothing is added to the library.
     */
    suspend fun renderTo(file: File, item: MediaItem, recipe: EditRecipe) = withContext(Dispatchers.Default) {
        val bitmap = draw(item, recipe) {}
        try {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "could not write the picture" } }
        } finally {
            bitmap.recycle()
        }
    }

    private fun draw(item: MediaItem, recipe: EditRecipe, onProgress: (Float) -> Unit): Bitmap {
        val source = loader.decodeWithin(item.id, MAX_PIXELS)
        try {
            val map = GeometryMap(source.width, source.height, recipe.geometry)
            val width = map.fullWidth
            val height = map.fullHeight
            val out = createBitmap(width, height)
            val bandRows = (BAND_PIXELS / width).coerceIn(MIN_BAND_ROWS, MAX_BAND_ROWS)
            EditRenderer.renderBands(BitmapPixelSource(source), recipe, width, height, bandRows) { top, rows, pixels ->
                out.setPixels(pixels, 0, width, 0, top, width, rows)
                onProgress((top + rows).toFloat() / height)
            }
            return out
        } finally {
            source.recycle()
        }
    }

    private fun store(item: MediaItem, bitmap: Bitmap): SavedCopy {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, item.displayName.substringBeforeLast('.') + SUFFIX + ".jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, folderFor(item))
            put(MediaStore.Images.Media.DATE_TAKEN, item.takenAt)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw IOException("could not create the copy")
        try {
            resolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "could not write the copy" } }
                ?: throw IOException("could not open the copy")
            val kept = copyMetadata(item, uri, bitmap.width, bitmap.height)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return SavedCopy(uri, kept)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Next to the original if that is in a place apps may write pictures to, otherwise in Pictures/eikon. */
    private fun folderFor(item: MediaItem): String {
        val original = item.relativePath.orEmpty()
        return if (original.startsWith("DCIM/") || original.startsWith("Pictures/")) original else "Pictures/eikon/"
    }

    /** Best effort: a copy without metadata is still a good copy, so a failure here is reported, not thrown. */
    private fun copyMetadata(item: MediaItem, copy: Uri, width: Int, height: Int): Boolean = try {
        val resolver = context.contentResolver
        val original = if (access.canReadLocation()) MediaStore.setRequireOriginal(item.uri) else item.uri
        val tags = resolver.openInputStream(original)?.use { stream ->
            val exif = ExifInterface(stream)
            COPIED_TAGS.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }
        }.orEmpty()
        resolver.openFileDescriptor(copy, "rw")?.use { pfd ->
            val target = ExifInterface(pfd.fileDescriptor)
            tags.forEach { (tag, value) -> target.setAttribute(tag, value) }
            target.setAttribute(ExifInterface.TAG_SOFTWARE, "eikon")
            target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            target.saveAttributes()
        }
        tags.isNotEmpty()
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    companion object {
        /** About 24 megapixels: enough for any phone camera except the biggest sensors' full mode, and small enough not to run out of memory. */
        const val MAX_PIXELS = 24_000_000L
        private const val JPEG_QUALITY = 95
        private const val SUFFIX = "_edit"
        private const val BAND_PIXELS = 2_000_000
        private const val MIN_BAND_ROWS = 16
        private const val MAX_BAND_ROWS = 512

        /** What describes the shot rather than the file. Orientation and dimensions are set for the new pixels instead. */
        private val COPIED_TAGS = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_DATETIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        )
    }
}
