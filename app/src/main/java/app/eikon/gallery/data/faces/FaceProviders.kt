package app.eikon.gallery.data.faces

import android.content.Context
import android.graphics.ImageDecoder
import app.eikon.gallery.data.embedding.ModelStore
import app.eikon.gallery.data.embedding.RgbImage
import app.eikon.gallery.data.indexing.StageUnavailableException
import app.eikon.gallery.domain.mediaContentUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** Owns the two face models for the length of an analysis run; they are loaded once per run, not per photo. */
@Singleton
class FaceIndexerProvider @Inject constructor(
    private val store: ModelStore,
) {
    private var detector: FaceDetector? = null
    private var embedder: FaceEmbedder? = null
    private var indexer: FaceIndexer? = null

    /** Throws [StageUnavailableException] if a model cannot be loaded, so the photo is not blamed for it. */
    @Synchronized
    fun get(): FaceIndexer {
        indexer?.let { return it }
        return try {
            val newDetector = YuNetFaceDetector(store.map(FACE_DETECTOR_MODEL)).also { detector = it }
            val newEmbedder = SFaceEmbedder(store.map(FACE_EMBEDDER_MODEL)).also { embedder = it }
            FaceIndexer(newDetector, newEmbedder).also { indexer = it }
        } catch (e: Exception) {
            close()
            throw StageUnavailableException("The face models could not be loaded", e)
        } catch (e: LinkageError) {
            close()
            throw StageUnavailableException("The model runtime could not be loaded", e)
        }
    }

    @Synchronized
    fun close() {
        detector?.close()
        embedder?.close()
        detector = null
        embedder = null
        indexer = null
    }
}

/** Decodes a photo upright with its long side at most [FacePolicy.MAX_IMAGE_EDGE] pixels, enough to find faces. */
@Singleton
class FaceImageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun load(mediaId: Long): RgbImage {
        val source = ImageDecoder.createSource(context.contentResolver, mediaContentUri(mediaId, isVideo = false))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = max(info.size.width, info.size.height)
            if (longSide > FacePolicy.MAX_IMAGE_EDGE) {
                val ratio = FacePolicy.MAX_IMAGE_EDGE.toDouble() / longSide
                decoder.setTargetSize((info.size.width * ratio).roundToInt(), (info.size.height * ratio).roundToInt())
            }
        }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return RgbImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }
}
