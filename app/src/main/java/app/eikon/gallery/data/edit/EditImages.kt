package app.eikon.gallery.data.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import app.eikon.gallery.data.embedding.RgbImage
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.EditRenderer
import app.eikon.gallery.domain.edit.ImagePixelSource
import app.eikon.gallery.domain.edit.PixelSource
import app.eikon.gallery.domain.mediaContentUri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.sqrt

/** A decoded bitmap as the renderer's source: the box asked for is read straight out of the bitmap, never the whole of it. */
class BitmapPixelSource(private val bitmap: Bitmap) : PixelSource {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun read(x: Int, y: Int, w: Int, h: Int, out: IntArray) {
        bitmap.getPixels(out, 0, w, x, y, w, h)
    }
}

/** A bitmap as a plain picture in memory (only for small ones: thumbnails and previews). */
fun Bitmap.toRgbImage(): RgbImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return RgbImage(width, height, pixels)
}

fun RgbImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

/**
 * This picture with [recipe] drawn on it, as a new bitmap; the bitmap itself is not changed. Meant for thumbnails and screen-sized pictures,
 * which are held in memory whole (a full-size export goes through [EditExporter], band by band).
 */
fun Bitmap.withRecipe(recipe: EditRecipe): Bitmap {
    if (recipe.isIdentity) return this
    val readable = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    return EditRenderer.render(ImagePixelSource(readable.toRgbImage()), recipe, max(readable.width, readable.height)).toBitmap()
}

/**
 * Decodes photos for editing. Everything is decoded as sRGB (wide-gamut photos are converted, so an edit looks the same everywhere and
 * the arithmetic of the recipe is well defined) and to a software bitmap, so its pixels can be read back.
 */
@Singleton
class EditImageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** A photo no larger than [maxEdge] on its longest side (never enlarged), upright. */
    fun decode(mediaId: Long, maxEdge: Int): Bitmap = decode(mediaId) { width, height ->
        val scale = maxEdge.toDouble() / max(width, height)
        if (scale < 1.0) (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1) else null
    }

    /** A photo at full resolution, or reduced to at most [maxPixels] pixels if it is bigger. */
    fun decodeWithin(mediaId: Long, maxPixels: Long): Bitmap = decode(mediaId) { width, height ->
        val pixels = width.toLong() * height
        if (pixels > maxPixels) {
            val scale = sqrt(maxPixels.toDouble() / pixels)
            (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
        } else {
            null
        }
    }

    private fun decode(mediaId: Long, target: (Int, Int) -> Pair<Int, Int>?): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, mediaContentUri(mediaId, isVideo = false))
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            target(info.size.width, info.size.height)?.let { (w, h) -> decoder.setTargetSize(w, h) }
        }
    }
}
