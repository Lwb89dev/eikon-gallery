package app.eikon.gallery.data.duplicates

import app.eikon.gallery.data.embedding.RgbImage
import kotlin.math.PI
import kotlin.math.cos

/**
 * A 64-bit fingerprint of what a picture looks like, so that copies that differ in file (recompressed, resized, sent through
 * a messaging app) get the same or almost the same number. It is the classic DCT hash: the picture is turned into
 * 32 x 32 shades of gray, its lowest 8 x 8 spatial frequencies are computed, and each is recorded as above or below the
 * median. Two pictures are "the same" when few bits differ ([distance]).
 *
 * Measured on 1,000 photos and 7 kinds of copy (docs/ML.md): JPEG at quality 40 and 70, half size, a messaging-app style
 * re-encode, +15% brightness and a 1 degree rotation all stayed within 4 bits for 96-100% of the photos; the closest pair
 * of different photos among the 499,500 pairs was 14 bits apart.
 */
object PerceptualHash {
    const val SIZE = 32
    private const val LOW = 8

    /** Copies within this many bits are treated as the same picture. */
    const val DUPLICATE_DISTANCE = 6

    private val COSINE: Array<DoubleArray> =
        Array(SIZE) { k -> DoubleArray(SIZE) { n -> cos(PI * (2 * n + 1) * k / (2 * SIZE)) } }

    /** The fingerprint of [image], of any size (a picture already reduced to about 64 x 64 is enough and much cheaper). */
    fun of(image: RgbImage): Long = fromGray(boxResize(luma(image), image.width, image.height))

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Luma as the common gray conversion does it (ITU-R 601), one number per pixel. */
    private fun luma(image: RgbImage): DoubleArray = DoubleArray(image.pixels.size) {
        val p = image.pixels[it]
        (((p shr 16) and 0xFF) * 19595 + ((p shr 8) and 0xFF) * 38470 + (p and 0xFF) * 7471 + 0x8000 shr 16).toDouble()
    }

    /** Area average down (or up) to [SIZE] x [SIZE]: each new pixel is the exact average of the part of the picture it covers. */
    private fun boxResize(gray: DoubleArray, width: Int, height: Int): DoubleArray {
        val horizontal = DoubleArray(SIZE * height)
        for (y in 0 until height) average(gray, y * width, 1, width, horizontal, y * SIZE, 1)
        val out = DoubleArray(SIZE * SIZE)
        for (x in 0 until SIZE) average(horizontal, x, SIZE, height, out, x, SIZE)
        return out
    }

    /** Averages a line of [length] values (starting at [from], every [step]) down to [SIZE] values written to [to]. */
    private fun average(source: DoubleArray, from: Int, step: Int, length: Int, target: DoubleArray, to: Int, targetStep: Int) {
        val scale = length.toDouble() / SIZE
        for (i in 0 until SIZE) {
            val start = i * scale
            val end = (i + 1) * scale
            var sum = 0.0
            var index = start.toInt()
            while (index < end && index < length) {
                val overlap = minOf(end, index + 1.0) - maxOf(start, index.toDouble())
                sum += source[from + index * step] * overlap
                index++
            }
            target[to + i * targetStep] = sum / scale
        }
    }

    /** The hash of an already reduced [SIZE] x [SIZE] gray picture (row by row). Public so tests can feed exact numbers. */
    fun fromGray(gray: DoubleArray): Long {
        require(gray.size == SIZE * SIZE) { "expected a $SIZE x $SIZE picture" }
        val rows = Array(LOW) { u -> DoubleArray(SIZE) { x -> (0 until SIZE).sumOf { y -> COSINE[u][y] * gray[y * SIZE + x] } } }
        val coefficients = DoubleArray(LOW * LOW) { i -> val u = i / LOW; val v = i % LOW; (0 until SIZE).sumOf { x -> rows[u][x] * COSINE[v][x] } }
        val median = coefficients.drop(1).sorted()[(LOW * LOW - 1) / 2]
        var hash = 0L
        for (i in coefficients.indices) if (coefficients[i] > median) hash = hash or (1L shl i)
        return hash
    }
}
