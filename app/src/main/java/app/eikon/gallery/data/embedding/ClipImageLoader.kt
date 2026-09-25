package app.eikon.gallery.data.embedding

import android.content.Context
import android.graphics.ImageDecoder
import app.eikon.gallery.domain.mediaContentUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Decodes a photo straight to the size the image model needs, which is much cheaper than decoding it in full. */
@Singleton
class ClipImageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** The photo, oriented as it is meant to be seen, scaled so its shorter side is 224 pixels and cropped to the central square. */
    fun load(mediaId: Long): RgbImage {
        val source = ImageDecoder.createSource(context.contentResolver, mediaContentUri(mediaId, isVideo = false))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (width, height) = ClipImagePreprocessor.decodeSize(info.size.width, info.size.height)
            decoder.setTargetSize(width, height)
        }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return RgbImage(bitmap.width, bitmap.height, pixels).centerCrop(ClipImagePreprocessor.SIZE)
        } finally {
            bitmap.recycle()
        }
    }
}
