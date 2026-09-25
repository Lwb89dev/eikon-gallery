package app.eikon.gallery.data.faces

import app.eikon.gallery.data.embedding.RgbImage
import kotlin.math.max
import kotlin.math.min

/** A face ready to be stored: where it is in the picture (as fractions of its width and height) and its vector. */
class NewFace(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    val vector: FloatArray,
)

/**
 * Which detected faces are worth keeping. Faces too small to describe reliably would only make noise in
 * People: on 1,500 photos shrunk step by step, the description of a face 43 pixels wide still matched its
 * full-size self well (mean cosine 0.93) but at 28 pixels it dropped to 0.86 and at 23 to 0.78
 * (docs/ML.md). The detector's own confidence must also be high, and a crowd is capped so one group photo
 * cannot flood the library with faces nobody will look at.
 */
object FacePolicy {
    const val MIN_FACE_PIXELS = 36f
    const val MAX_FACES_PER_PHOTO = 12
    const val MAX_IMAGE_EDGE = 1280

    fun keep(faces: List<DetectedFace>): List<DetectedFace> = faces
        .filter { it.width >= MIN_FACE_PIXELS && it.height >= MIN_FACE_PIXELS }
        .sortedByDescending { it.score }
        .take(MAX_FACES_PER_PHOTO)
}

/** Finds the faces of one photo and describes each of them. Knows nothing about databases or people. */
class FaceIndexer(private val detector: FaceDetector, private val embedder: FaceEmbedder) {
    fun describe(image: RgbImage): List<NewFace> = FacePolicy.keep(detector.detect(image)).map { face ->
        val vector = embedder.embed(FaceAligner.crop(image, face.landmarks))
        NewFace(
            left = clamp(face.left / image.width),
            top = clamp(face.top / image.height),
            right = clamp(face.right / image.width),
            bottom = clamp(face.bottom / image.height),
            score = face.score,
            vector = vector,
        )
    }

    private fun clamp(fraction: Float) = min(1f, max(0f, fraction))
}

/** The 128 floats of a face as bytes for the database, little-endian. */
object FaceVectors {
    fun toBytes(vector: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    fun fromBytes(bytes: ByteArray): FloatArray {
        val out = FloatArray(bytes.size / Float.SIZE_BYTES)
        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }
}
