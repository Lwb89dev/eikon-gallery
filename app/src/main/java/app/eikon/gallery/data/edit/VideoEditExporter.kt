package app.eikon.gallery.data.edit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.edit.EditRecipe
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.media3.common.MediaItem as PlayerMediaItem

/**
 * "Save a copy" for video: renders [EditRecipe.trim] and the geometry and color [VideoEditEffects] supports into a **new** file next to the
 * original, which is never touched, the same promise a photo's copy makes. Media3's `Transformer` needs a real filesystem path to write to
 * (not a `Uri`), so this renders into the app's cache first and copies the result into MediaStore, deleting the temporary file either way.
 *
 * **Not run on a device.** `Transformer` must be driven from a thread with a `Looper`, which is why every call to it happens on
 * [Dispatchers.Main]; whether that is enough, and how long a render takes on a phone, is unchecked.
 */
@OptIn(UnstableApi::class)
@Singleton
class VideoEditExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun saveCopy(item: MediaItem, recipe: EditRecipe, onProgress: (Float) -> Unit = {}): Uri {
        val outputFile = File(context.cacheDir, "$TEMP_PREFIX${item.id}_${System.currentTimeMillis()}.mp4")
        return try {
            render(item, recipe, outputFile, onProgress)
            store(item, outputFile)
        } finally {
            outputFile.delete()
        }
    }

    private suspend fun render(item: MediaItem, recipe: EditRecipe, outputFile: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.Main) {
        val effects = Effects(emptyList(), VideoEditEffects.forRecipe(recipe, item.width, item.height))
        val mediaItem = PlayerMediaItem.Builder().setUri(item.uri).setClippingConfiguration(VideoEditEffects.clippingConfigOf(recipe.trim)).build()
        val edited = EditedMediaItem.Builder(mediaItem).setEffects(effects).build()
        val transformer = Transformer.Builder(context).setLooper(Looper.myLooper() ?: Looper.getMainLooper()).build()
        try {
            suspendCancellableCoroutine { continuation ->
                transformer.addListener(
                    object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWith(Result.failure(exportException))
                        }
                    },
                )
                transformer.start(edited, outputFile.absolutePath)
                continuation.invokeOnCancellation { transformer.cancel() }
            }
        } finally {
            reportProgress(transformer, onProgress)
        }
    }

    /** One last read of the progress Transformer itself kept, now that it has finished, so the bar always ends at complete. */
    private fun reportProgress(transformer: Transformer, onProgress: (Float) -> Unit) {
        val holder = ProgressHolder()
        if (transformer.getProgress(holder) != Transformer.PROGRESS_STATE_UNAVAILABLE) onProgress(holder.progress / PERCENT)
    }

    private fun store(item: MediaItem, rendered: File): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, item.displayName.substringBeforeLast('.') + SUFFIX + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, folderFor(item))
            put(MediaStore.Video.Media.DATE_TAKEN, item.takenAt)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("could not create the copy")
        try {
            resolver.openOutputStream(uri)?.use { out -> rendered.inputStream().use { it.copyTo(out) } } ?: throw IOException("could not open the copy")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Next to the original if that is in a place apps may write videos to, otherwise in Movies/eikon. */
    private fun folderFor(item: MediaItem): String {
        val original = item.relativePath.orEmpty()
        return if (original.startsWith("DCIM/") || original.startsWith("Movies/")) original else "Movies/eikon/"
    }

    private companion object {
        const val TEMP_PREFIX = "eikon-export-"
        const val SUFFIX = "_edit"
        const val PERCENT = 100f
    }
}
