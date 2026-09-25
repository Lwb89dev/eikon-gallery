package app.eikon.gallery.data.faces

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A face found in an image, in the pixels of the image it was found in. [landmarks] are five points as
 * x, y pairs: the two eyes (the subject's right eye first, which is on the left of the picture), the nose
 * tip and the two mouth corners.
 */
class DetectedFace(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val landmarks: FloatArray,
    val score: Float,
) {
    init {
        require(landmarks.size == LANDMARK_VALUES) { "a face has five landmarks" }
    }

    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /** The same face in an image [factor] times bigger in each direction. */
    fun scaled(factor: Int) = DetectedFace(
        left * factor, top * factor, width * factor, height * factor,
        FloatArray(landmarks.size) { landmarks[it] * factor }, score,
    )

    companion object {
        const val LANDMARK_VALUES = 10
    }
}

/**
 * Turns the raw outputs of the YuNet face detector (OpenCV Zoo, MIT) into faces. YuNet looks at the image
 * on three grids, one cell every 8, 16 and 32 pixels; for each cell it says how likely a face is centred
 * there (`cls` and `obj`), where the box is relative to the cell (`bbox`) and where the five landmarks are
 * (`kps`). This is the decoding OpenCV's own `FaceDetectorYN` does, and a test compares the two.
 */
object YuNetDecoder {
    val STRIDES = intArrayOf(8, 16, 32)

    /** Divisor the input's width and height must be a multiple of. */
    const val DIVISOR = 32

    private const val LANDMARKS = 5

    /** [outputs] by name (`cls_8`, `obj_16`, `bbox_32`, `kps_8`...) for an input of [paddedWidth] x [paddedHeight]. */
    fun decode(
        outputs: Map<String, FloatArray>,
        paddedWidth: Int,
        paddedHeight: Int,
        scoreThreshold: Float,
        nmsThreshold: Float,
    ): List<DetectedFace> {
        val candidates = STRIDES.flatMap { stride -> decodeGrid(outputs, stride, paddedWidth / stride, paddedHeight / stride, scoreThreshold) }
        return suppressOverlaps(candidates.sortedByDescending { it.score }, nmsThreshold)
    }

    private fun decodeGrid(outputs: Map<String, FloatArray>, stride: Int, columns: Int, rows: Int, threshold: Float): List<DetectedFace> {
        val cls = outputs.getValue("cls_$stride")
        val obj = outputs.getValue("obj_$stride")
        val box = outputs.getValue("bbox_$stride")
        val points = outputs.getValue("kps_$stride")
        val faces = ArrayList<DetectedFace>()
        for (cell in 0 until columns * rows) {
            val score = sqrt(cls[cell].coerceIn(0f, 1f) * obj[cell].coerceIn(0f, 1f))
            if (score < threshold) continue
            val column = cell % columns
            val row = cell / columns
            val width = exp(box[cell * 4 + 2]) * stride
            val height = exp(box[cell * 4 + 3]) * stride
            val centreX = (column + box[cell * 4]) * stride
            val centreY = (row + box[cell * 4 + 1]) * stride
            val landmarks = FloatArray(LANDMARKS * 2) { i ->
                val offset = points[cell * LANDMARKS * 2 + i]
                (offset + if (i % 2 == 0) column else row) * stride
            }
            faces += DetectedFace(centreX - width / 2, centreY - height / 2, width, height, landmarks, score)
        }
        return faces
    }

    /** Greedy non-maximum suppression, best first: a face is dropped if it overlaps a kept one by more than [threshold]. */
    fun suppressOverlaps(sortedBestFirst: List<DetectedFace>, threshold: Float): List<DetectedFace> {
        val kept = ArrayList<DetectedFace>()
        for (face in sortedBestFirst) {
            if (kept.none { overlap(it, face) > threshold }) kept += face
        }
        return kept
    }

    /** Intersection over union of two boxes. */
    private fun overlap(a: DetectedFace, b: DetectedFace): Float {
        val width = min(a.right, b.right) - max(a.left, b.left)
        val height = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (width <= 0f || height <= 0f) return 0f
        val intersection = width * height
        return intersection / (a.width * a.height + b.width * b.height - intersection)
    }
}
