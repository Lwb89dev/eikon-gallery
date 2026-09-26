package app.eikon.gallery.data.metadata

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.data.db.MetadataOriginalEntity
import app.eikon.gallery.data.indexing.IndexingRepository
import app.eikon.gallery.domain.EditableFormats
import app.eikon.gallery.domain.ExifFields
import app.eikon.gallery.domain.ExifFormat
import app.eikon.gallery.domain.ExifText
import app.eikon.gallery.domain.GeoPoint
import app.eikon.gallery.domain.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A change to what a photo's file says about itself. */
sealed interface MetadataChange {
    /** The photo was taken at [local] wall-clock time, in a place [offset] from UTC. */
    data class Date(val local: LocalDateTime, val offset: ZoneOffset) : MetadataChange

    /** The photo was taken at [point]; null takes the location out of the file. */
    data class Location(val point: GeoPoint?) : MetadataChange

    data object RevertDate : MetadataChange
    data object RevertLocation : MetadataChange
}

class MetadataException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** The file's format cannot be written by Android's EXIF library (HEIC, GIF, videos). */
        NOT_WRITABLE,

        /** Without the photo-location permission Android shows eikon a copy with the location taken out, and writing that back would destroy it. */
        NEEDS_LOCATION_PERMISSION,

        NOTHING_TO_REVERT,

        /** An earlier change to this photo was interrupted and its file must be restored first ([MetadataWriter.restoreInterrupted]). */
        INTERRUPTED,

        /** It did not work; the file was put back as it was (or, if that too failed, the original is kept in eikon's private storage). */
        FAILED,
    }
}

/**
 * Changes the date or the location written inside a photo, and never at the price of the photo. What it does, in order:
 *
 * 1. Refuses formats it cannot write, and refuses to work without the photo-location permission (see [MetadataException.Reason.NEEDS_LOCATION_PERMISSION]).
 * 2. Copies the **original bytes** of the file (asked for unredacted) to a safety copy in eikon's private storage.
 * 3. Makes a working copy and changes the tags **there**, after writing down in the database what the file said before (only the first time, so the true original is never lost).
 * 4. Only then writes the working copy over the photo (the system asked the user first: `MediaStore.createWriteRequest`), and reads the photo back to check it is
 *    byte for byte what was written.
 * 5. If anything at all fails, the safety copy is written back over the photo and the record in the database is undone. The safety copy is deleted only after everything
 *    worked, or after it was restored.
 *
 * The caption is not here: it never touches a file.
 */
@Singleton
class MetadataWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MetadataRepository,
    private val access: MediaAccessChecker,
    private val indexing: IndexingRepository,
) {
    private val lock = Mutex()
    private val resolver get() = context.contentResolver
    private val directory get() = File(context.filesDir, DIRECTORY)

    suspend fun apply(item: MediaItem, change: MetadataChange) = withContext(Dispatchers.IO) { lock.withLock { edit(item, change) } }

    /** True if an earlier change to this photo was interrupted (the app was killed mid-write) and its safety copy is still there. */
    suspend fun isInterrupted(item: MediaItem): Boolean = withContext(Dispatchers.IO) { safetyCopy(item).exists() }

    /** Puts the file back as it was before an interrupted change. The system must already have allowed writing to it. */
    suspend fun restoreInterrupted(item: MediaItem) = withContext(Dispatchers.IO) {
        lock.withLock {
            val safety = safetyCopy(item)
            if (safety.exists() && !writeOver(item, safety)) throw MetadataException(MetadataException.Reason.FAILED)
            safety.delete()
        }
    }

    /** What one attempt has done so far, so a failure knows how much to undo. */
    private class Attempt {
        /** The field whose original this attempt wrote down. */
        var kept: String? = null

        /** True once the photo itself has been written over. */
        var written = false
    }

    private suspend fun edit(item: MediaItem, change: MetadataChange) {
        requireEditable(item)
        val safety = safetyCopy(item)
        if (safety.exists()) throw MetadataException(MetadataException.Reason.INTERRUPTED)
        directory.mkdirs()
        val work = File(directory, "${item.id}.edit")
        val attempt = Attempt()
        try {
            copyOriginalTo(item, safety)
            safety.copyTo(work, overwrite = true)
            attempt.kept = changeCopy(item, change, work)
            replace(item, work, attempt)
            afterSuccess(item, change)
            safety.delete()
        } catch (e: Exception) {
            undo(item, safety, attempt)
            if (e is CancellationException || e is MetadataException) throw e
            throw MetadataException(MetadataException.Reason.FAILED, e)
        } finally {
            work.delete()
        }
    }

    private fun requireEditable(item: MediaItem) {
        if (!EditableFormats.canWrite(item.mimeType)) throw MetadataException(MetadataException.Reason.NOT_WRITABLE)
        if (!access.canReadLocation()) throw MetadataException(MetadataException.Reason.NEEDS_LOCATION_PERMISSION)
    }

    /** Changes the tags in the working copy; returns the field whose original was written down by this call (to be forgotten if the change is undone). */
    private suspend fun changeCopy(item: MediaItem, change: MetadataChange, work: File): String? = when (change) {
        is MetadataChange.Date -> editDate(item, work, change)
        is MetadataChange.Location -> editLocation(item, work, change.point)
        MetadataChange.RevertDate -> revert(item, work, MetadataOriginalEntity.FIELD_DATE)
        MetadataChange.RevertLocation -> revert(item, work, MetadataOriginalEntity.FIELD_LOCATION)
    }

    private suspend fun editDate(item: MediaItem, work: File, change: MetadataChange.Date): String? {
        val kept = keepOriginal(item, work, MetadataOriginalEntity.FIELD_DATE)
        val text = ExifText.dateTime(change.local)
        val offset = ExifText.offset(change.offset)
        val values = mapOf("DateTimeOriginal" to text, "DateTimeDigitized" to text, "OffsetTimeOriginal" to offset, "OffsetTimeDigitized" to offset)
        MetadataFileEditor.write(work, ExifFields.DATE_TAGS, values)
        return kept
    }

    private suspend fun editLocation(item: MediaItem, work: File, point: GeoPoint?): String? {
        val kept = keepOriginal(item, work, MetadataOriginalEntity.FIELD_LOCATION)
        if (point == null) MetadataFileEditor.write(work, ExifFields.GPS_TAGS, emptyMap()) else MetadataFileEditor.writeLocation(work, point)
        return kept
    }

    private suspend fun revert(item: MediaItem, work: File, field: String): String? {
        val original = repository.original(item.id, field) ?: throw MetadataException(MetadataException.Reason.NOTHING_TO_REVERT)
        MetadataFileEditor.write(work, ExifFields.tagsOf(field), original)
        return null
    }

    /** Writes down what the file says now, unless the original of [field] is already kept. Returns [field] if this call kept it. */
    private suspend fun keepOriginal(item: MediaItem, work: File, field: String): String? {
        if (repository.hasOriginal(item.id, field)) return null
        repository.keepOriginal(item.id, field, MetadataFileEditor.snapshot(work, ExifFields.tagsOf(field)))
        return field
    }

    /** Writes the checked working copy over the photo and reads the photo back to be sure. */
    private fun replace(item: MediaItem, work: File, attempt: Attempt) {
        attempt.written = true // from here the photo may have changed, even if the write fails half way
        if (!writeOver(item, work)) throw MetadataException(MetadataException.Reason.FAILED)
        updateMediaStore(item, work)
    }

    /** Writes [source] over the photo and checks that what is there now is exactly [source]. */
    private fun writeOver(item: MediaItem, source: File): Boolean = try {
        val out = resolver.openOutputStream(item.uri, "wt") ?: throw IOException("could not open ${item.displayName} for writing")
        out.use { sink -> source.inputStream().use { it.copyTo(sink) } }
        digest(source.inputStream()) == digest(resolver.openInputStream(MediaStore.setRequireOriginal(item.uri)) ?: throw IOException("could not read back"))
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /**
     * Puts everything back after a failure (or a cancellation): the photo if it was written, and the record of its original if this attempt made it. If the photo cannot be put
     * back, the safety copy stays where it is: it is the original, and [isInterrupted] will offer to restore it.
     */
    private suspend fun undo(item: MediaItem, safety: File, attempt: Attempt) = withContext(NonCancellable) {
        val intact = !attempt.written || writeOver(item, safety)
        if (intact) safety.delete()
        attempt.kept?.let { repository.forgetOriginal(item.id, it) }
    }

    private suspend fun afterSuccess(item: MediaItem, change: MetadataChange) {
        when (change) {
            MetadataChange.RevertDate -> repository.forgetOriginal(item.id, MetadataOriginalEntity.FIELD_DATE)
            MetadataChange.RevertLocation -> {
                repository.forgetOriginal(item.id, MetadataOriginalEntity.FIELD_LOCATION)
                indexing.forgetPlace(item.id)
            }
            is MetadataChange.Location -> indexing.forgetPlace(item.id)
            is MetadataChange.Date -> Unit
        }
    }

    /** MediaStore re-reads the file on its own, but is told the new date at once so the library can move the photo without waiting. */
    private fun updateMediaStore(item: MediaItem, work: File) {
        val takenAt = takenAtOf(work) ?: return
        try {
            resolver.update(item.uri, ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, takenAt) }, null, null)
        } catch (_: SecurityException) {
            // Not allowed to touch the row: the file is right, and MediaStore will catch up when it next scans it.
        } catch (_: IllegalArgumentException) {
            // The same.
        }
    }

    private fun takenAtOf(file: File): Long? {
        val exif = ExifInterface(file.absolutePath)
        val capture = ExifFormat.captureTime(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)) ?: return null
        val zone = capture.offset ?: ZoneId.systemDefault().rules.getOffset(capture.local)
        return capture.local.toInstant(zone).toEpochMilli()
    }

    private fun copyOriginalTo(item: MediaItem, target: File) {
        val input = resolver.openInputStream(MediaStore.setRequireOriginal(item.uri)) ?: throw MetadataException(MetadataException.Reason.FAILED)
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
    }

    private fun safetyCopy(item: MediaItem) = File(directory, "${item.id}.orig")

    private fun digest(input: java.io.InputStream): String = input.use { stream ->
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        var read = stream.read(buffer)
        while (read >= 0) {
            sha.update(buffer, 0, read)
            read = stream.read(buffer)
        }
        sha.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DIRECTORY = "metadata-safety"
        const val BUFFER = 64 * 1024
    }
}
