package app.eikon.gallery.data.faces

import app.eikon.gallery.data.embedding.RgbImage
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Cuts a face out upright and at a fixed scale, the way the recognition model was trained: the five
 * landmarks are mapped by the closest possible similarity transform (rotation, one scale, translation) onto
 * the standard positions of a 112 x 112 face, and the image is sampled through that transform with
 * bilinear interpolation, black outside the picture. It reproduces OpenCV's `FaceRecognizerSF.alignCrop`;
 * a test compares embeddings made from both.
 */
object FaceAligner {
    const val SIZE = 112

    /** Where the eyes, nose and mouth corners sit in the standard 112 x 112 face (x, y pairs). */
    private val TEMPLATE = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

    /** The 2 x 3 matrix `[a b tx; c d ty]` mapping picture coordinates to the aligned face. */
    fun transform(landmarks: FloatArray): FloatArray {
        val points = TEMPLATE.size / 2
        val source = centroid(landmarks)
        val target = centroid(TEMPLATE)
        var dot = 0.0
        var cross = 0.0
        var variance = 0.0
        for (i in 0 until points) {
            val sx = landmarks[2 * i] - source[0]
            val sy = landmarks[2 * i + 1] - source[1]
            val tx = TEMPLATE[2 * i] - target[0]
            val ty = TEMPLATE[2 * i + 1] - target[1]
            dot += sx * tx + sy * ty
            cross += sx * ty - sy * tx
            variance += sx * sx + sy * sy
        }
        val angle = atan2(cross, dot)
        val scale = hypot(dot, cross) / variance
        val a = scale * cos(angle)
        val b = -scale * sin(angle)
        val c = scale * sin(angle)
        return floatArrayOf(
            a.toFloat(), b.toFloat(), (target[0] - a * source[0] - b * source[1]).toFloat(),
            c.toFloat(), a.toFloat(), (target[1] - c * source[0] - a * source[1]).toFloat(),
        )
    }

    /** The aligned [SIZE] x [SIZE] face of the face whose landmarks are [landmarks] in [image]. */
    fun crop(image: RgbImage, landmarks: FloatArray): RgbImage {
        val forward = transform(landmarks)
        val a = forward[0]
        val b = forward[1]
        val tx = forward[2]
        val c = forward[3]
        val d = forward[4]
        val ty = forward[5]
        // Inverse of a similarity: rotate back, undo the scale, undo the shift.
        val det = a * d - b * c
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        val itx = -(ia * tx + ib * ty)
        val ity = -(ic * tx + id * ty)
        val out = IntArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) out[y * SIZE + x] = sample(image, ia * x + ib * y + itx, ic * x + id * y + ity)
        }
        return RgbImage(SIZE, SIZE, out)
    }

    private fun centroid(points: FloatArray): DoubleArray {
        var x = 0.0
        var y = 0.0
        for (i in 0 until points.size / 2) {
            x += points[2 * i]
            y += points[2 * i + 1]
        }
        return doubleArrayOf(x / (points.size / 2), y / (points.size / 2))
    }

    /** Bilinear sample at ([x], [y]); pixels outside the image count as black. */
    private fun sample(image: RgbImage, x: Float, y: Float): Int {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        var red = 0f
        var green = 0f
        var blue = 0f
        for (dy in 0..1) {
            for (dx in 0..1) {
                val weight = (if (dx == 0) 1 - fx else fx) * (if (dy == 0) 1 - fy else fy)
                val pixel = pixelOrBlack(image, x0 + dx, y0 + dy)
                red += weight * ((pixel shr RED_SHIFT) and 0xFF)
                green += weight * ((pixel shr GREEN_SHIFT) and 0xFF)
                blue += weight * (pixel and 0xFF)
            }
        }
        return (0xFF shl ALPHA_SHIFT) or (round(red) shl RED_SHIFT) or (round(green) shl GREEN_SHIFT) or round(blue)
    }

    private fun pixelOrBlack(image: RgbImage, x: Int, y: Int): Int =
        if (x < 0 || y < 0 || x >= image.width || y >= image.height) 0 else image.pixels[y * image.width + x]

    private fun round(value: Float): Int = (value + 0.5f).toInt().coerceIn(0, 255)

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val ALPHA_SHIFT = 24
}
