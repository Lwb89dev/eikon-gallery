package app.eikon.gallery.data.faces

import app.eikon.gallery.data.embedding.RgbImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceIndexerTest {
    private fun landmarks(x: Float, y: Float) = floatArrayOf(x + 10, y + 10, x + 30, y + 10, x + 20, y + 20, x + 12, y + 30, x + 28, y + 30)

    private class FakeDetector(private val faces: List<DetectedFace>) : FaceDetector {
        override fun detect(image: RgbImage) = faces
        override fun close() = Unit
    }

    private class FakeEmbedder : FaceEmbedder {
        var calls = 0
        override fun embed(aligned: RgbImage): FloatArray {
            calls++
            return FloatArray(FACE_DIMENSIONS) { if (it == 0) 1f else 0f }
        }
        override fun close() = Unit
    }

    private val image = RgbImage(200, 100, IntArray(200 * 100) { 0xFF808080.toInt() })

    private fun face(x: Float, y: Float, size: Float, score: Float = 0.9f) = DetectedFace(x, y, size, size, landmarks(x, y), score)

    @Test
    fun boxesAreStoredAsFractionsOfThePicture() {
        val indexer = FaceIndexer(FakeDetector(listOf(face(50f, 10f, 40f))), FakeEmbedder())

        val stored = indexer.describe(image).single()

        assertEquals(0.25f, stored.left, 1e-6f)
        assertEquals(0.10f, stored.top, 1e-6f)
        assertEquals(0.45f, stored.right, 1e-6f)
        assertEquals(0.50f, stored.bottom, 1e-6f)
        assertEquals(FACE_DIMENSIONS, stored.vector.size)
    }

    @Test
    fun aBoxThatSticksOutOfThePictureIsClamped() {
        val indexer = FaceIndexer(FakeDetector(listOf(face(180f, 70f, 40f))), FakeEmbedder())
        val stored = indexer.describe(image).single()
        assertEquals(1f, stored.right, 0f)
        assertEquals(1f, stored.bottom, 0f)
    }

    @Test
    fun facesTooSmallToDescribeAreDroppedWithoutBeingEmbedded() {
        val embedder = FakeEmbedder()
        val tiny = face(10f, 10f, FacePolicy.MIN_FACE_PIXELS - 1)
        val ok = face(100f, 10f, FacePolicy.MIN_FACE_PIXELS)

        val stored = FaceIndexer(FakeDetector(listOf(tiny, ok)), embedder).describe(image)

        assertEquals(1, stored.size)
        assertEquals(1, embedder.calls)
    }

    @Test
    fun aCrowdIsCappedKeepingTheMostConfidentFaces() {
        val faces = (0 until 30).map { face(it * 5f, 10f, 40f, score = 0.8f + it * 0.001f) }
        val kept = FacePolicy.keep(faces)
        assertEquals(FacePolicy.MAX_FACES_PER_PHOTO, kept.size)
        assertTrue(kept.all { it.score >= 0.8f + (30 - FacePolicy.MAX_FACES_PER_PHOTO) * 0.001f - 1e-6f })
    }

    @Test
    fun noFacesGivesNothing() {
        assertTrue(FaceIndexer(FakeDetector(emptyList()), FakeEmbedder()).describe(image).isEmpty())
    }

    @Test
    fun aVectorSurvivesTheRoundTripToBytes() {
        val vector = FloatArray(FACE_DIMENSIONS) { (it - 64) / 100f }
        assertEquals(vector.toList(), FaceVectors.fromBytes(FaceVectors.toBytes(vector)).toList())
        assertEquals(FACE_DIMENSIONS * 4, FaceVectors.toBytes(vector).size)
    }
}
