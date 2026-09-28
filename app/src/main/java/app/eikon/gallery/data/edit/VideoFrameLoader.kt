package app.eikon.gallery.data.edit

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import app.eikon.gallery.domain.mediaContentUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One frame of a video, for the video editor's filter thumbnails: nothing here needs to touch every frame, only a single representative one. */
@Singleton
class VideoFrameLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** The frame closest to [atMs] into the video, or null if the file could not be read. */
    suspend fun frameAt(mediaId: Long, atMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, mediaContentUri(mediaId, isVideo = true))
            retriever.getFrameAtTime(atMs * MICROS_PER_MILLI, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: RuntimeException) {
            null
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val MICROS_PER_MILLI = 1000L
    }
}
