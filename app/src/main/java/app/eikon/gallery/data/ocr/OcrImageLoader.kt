package app.eikon.gallery.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import app.eikon.gallery.domain.mediaContentUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/** Decodes a photo at a size suited to OCR: large enough to read receipts, small enough to be quick. */
@Singleton
class OcrImageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** A software ARGB bitmap with EXIF rotation applied, scaled down so its long side is at most [MAX_EDGE_PX]. */
    fun load(mediaId: Long): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, mediaContentUri(mediaId, isVideo = false))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = max(info.size.width, info.size.height)
            if (longSide > MAX_EDGE_PX) {
                val ratio = MAX_EDGE_PX.toDouble() / longSide
                decoder.setTargetSize((info.size.width * ratio).roundToInt(), (info.size.height * ratio).roundToInt())
            }
        }
    }

    private companion object {
        const val MAX_EDGE_PX = 2000
    }
}
