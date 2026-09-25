package app.eikon.gallery.data.faces

import app.eikon.gallery.data.embedding.Embeddings
import app.eikon.gallery.data.embedding.OnnxModel
import app.eikon.gallery.data.embedding.RgbImage
import java.nio.ByteBuffer

/** Finds faces in an image. */
interface FaceDetector : AutoCloseable {
    fun detect(image: RgbImage): List<DetectedFace>
}

/** Turns an aligned 112 x 112 face into a vector: the same person's faces are close, different people's are not. */
interface FaceEmbedder : AutoCloseable {
    /** A unit vector of [FACE_DIMENSIONS] values. */
    fun embed(aligned: RgbImage): FloatArray
}

const val FACE_DIMENSIONS = 128

/** Model files as they are named in the assets. */
const val FACE_DETECTOR_MODEL = "yunet_face_detector.onnx"
const val FACE_EMBEDDER_MODEL = "sface_recognizer.onnx"

/**
 * YuNet (OpenCV Zoo, MIT): a 0.2 MB face detector that takes an image of any size (padded to a multiple of 32) as
 * blue-green-red numbers from 0 to 255, with no normalisation.
 */
class YuNetFaceDetector(
    model: ByteBuffer,
    private val scoreThreshold: Float = SCORE_THRESHOLD,
    threads: Int = DEFAULT_THREADS,
) : FaceDetector {
    private val onnx = OnnxModel(model, threads)

    /**
     * YuNet only finds faces up to about 300 pixels across, so a selfie or portrait that fills the frame would be
     * missed. The image is therefore also searched at half and at quarter size, and the results merged. On 200 photos
     * enlarged step by step, a single pass found 70% of faces 480 pixels across and 17% of those 576 across; with the
     * three passes all were found up to 576 (docs/ML.md). The extra passes cost about a third more time.
     */
    override fun detect(image: RgbImage): List<DetectedFace> {
        val found = ArrayList<DetectedFace>()
        var level = image
        var factor = 1
        repeat(LEVELS) {
            if (level.width < MIN_LEVEL_EDGE || level.height < MIN_LEVEL_EDGE) return@repeat
            detectOnce(level).mapTo(found) { it.scaled(factor) }
            level = level.halved()
            factor *= 2
        }
        return YuNetDecoder.suppressOverlaps(found.sortedByDescending { it.score }, NMS_THRESHOLD)
    }

    private fun detectOnce(image: RgbImage): List<DetectedFace> {
        val paddedWidth = roundUp(image.width)
        val paddedHeight = roundUp(image.height)
        val tensor = FaceInputs.detectorTensor(image, paddedWidth, paddedHeight)
        onnx.floatTensor(tensor, 1, 3, paddedHeight.toLong(), paddedWidth.toLong()).use { input ->
            val outputs = onnx.run(mapOf("input" to input))
            return YuNetDecoder.decode(outputs, paddedWidth, paddedHeight, scoreThreshold, NMS_THRESHOLD)
        }
    }

    override fun close() = onnx.close()

    private fun roundUp(size: Int) = (size + YuNetDecoder.DIVISOR - 1) / YuNetDecoder.DIVISOR * YuNetDecoder.DIVISOR

    companion object {
        const val SCORE_THRESHOLD = 0.8f
        const val NMS_THRESHOLD = 0.3f
        private const val LEVELS = 3

        /** A level is only searched while it is at least this big on both sides. */
        private const val MIN_LEVEL_EDGE = 96
        private const val DEFAULT_THREADS = 2
    }
}

/**
 * SFace (OpenCV Zoo, Apache-2.0): a MobileFaceNet trained with the SFace loss. It takes the aligned face as
 * red-green-blue numbers from 0 to 255 (its first layers do the normalisation) and returns 128 numbers.
 */
class SFaceEmbedder(model: ByteBuffer, threads: Int = DEFAULT_THREADS) : FaceEmbedder {
    private val onnx = OnnxModel(model, threads)

    override fun embed(aligned: RgbImage): FloatArray {
        val size = FaceAligner.SIZE.toLong()
        onnx.floatTensor(FaceInputs.embedderTensor(aligned), 1, 3, size, size).use { input ->
            return Embeddings.normalized(onnx.floats(mapOf("data" to input), "fc1"))
        }
    }

    override fun close() = onnx.close()

    private companion object {
        const val DEFAULT_THREADS = 2
    }
}

/** How images become the two models' inputs; plain arrays so it is tested without the models. */
object FaceInputs {
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8

    /** Blue, green and red planes, 0 to 255, [width] x [height], black where the image does not reach. */
    fun detectorTensor(image: RgbImage, width: Int, height: Int): FloatArray {
        val plane = width * height
        val out = FloatArray(3 * plane)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.pixels[y * image.width + x]
                val at = y * width + x
                out[at] = (pixel and 0xFF).toFloat()
                out[plane + at] = ((pixel shr GREEN_SHIFT) and 0xFF).toFloat()
                out[2 * plane + at] = ((pixel shr RED_SHIFT) and 0xFF).toFloat()
            }
        }
        return out
    }

    /** Red, green and blue planes of the 112 x 112 aligned face, 0 to 255. */
    fun embedderTensor(aligned: RgbImage): FloatArray {
        require(aligned.width == FaceAligner.SIZE && aligned.height == FaceAligner.SIZE) { "expected an aligned 112 x 112 face" }
        val plane = FaceAligner.SIZE * FaceAligner.SIZE
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val pixel = aligned.pixels[i]
            out[i] = ((pixel shr RED_SHIFT) and 0xFF).toFloat()
            out[plane + i] = ((pixel shr GREEN_SHIFT) and 0xFF).toFloat()
            out[2 * plane + i] = (pixel and 0xFF).toFloat()
        }
        return out
    }
}
