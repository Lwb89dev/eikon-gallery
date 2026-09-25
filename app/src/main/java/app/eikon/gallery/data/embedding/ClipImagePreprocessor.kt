package app.eikon.gallery.data.embedding

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What the CLIP image model expects, as its own preprocessing config defines it: resize so the shorter
 * side is 224, crop the central 224 x 224, scale to 0..1 and normalise each channel with CLIP's mean and
 * standard deviation, laid out channel by channel (CHW).
 *
 * The resizing itself is left to the platform decoder (it can decode straight to a small size, which is
 * far cheaper than decoding a 12-megapixel photo and shrinking it); [decodeSize] says what to ask for.
 */
object ClipImagePreprocessor {
    const val SIZE = 224

    private val MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
    private const val CHANNEL_MAX = 255f

    /** The size to decode a [width] x [height] photo to so that its shorter side is [SIZE]. */
    fun decodeSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = SIZE.toDouble() / min(width, height)
        return max(SIZE, (width * scale).roundToInt()) to max(SIZE, (height * scale).roundToInt())
    }

    /** The model input for an already cropped [SIZE] x [SIZE] image, 3 x 224 x 224 floats. */
    fun toTensor(image: RgbImage): FloatArray {
        require(image.width == SIZE && image.height == SIZE) { "expected ${SIZE}x$SIZE, got ${image.width}x${image.height}" }
        val plane = SIZE * SIZE
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val argb = image.pixels[i]
            out[i] = channel(argb shr RED_SHIFT, 0)
            out[plane + i] = channel(argb shr GREEN_SHIFT, 1)
            out[2 * plane + i] = channel(argb, 2)
        }
        return out
    }

    private fun channel(packed: Int, index: Int): Float = ((packed and 0xFF) / CHANNEL_MAX - MEAN[index]) / STD[index]

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
