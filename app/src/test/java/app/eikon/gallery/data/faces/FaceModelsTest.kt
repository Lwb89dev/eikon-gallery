package app.eikon.gallery.data.faces

import app.eikon.gallery.data.embedding.ModelTestSupport
import app.eikon.gallery.data.embedding.RgbImage
import javax.imageio.ImageIO
import kotlin.math.abs
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * The real face models through ONNX Runtime, compared with OpenCV's own pipeline (`FaceDetectorYN` and
 * `FaceRecognizerSF`, run in Python) on the same public-domain photo: same box and landmarks, the same
 * aligned crop, the same face vector. Then, on the other sample photos, no face is invented.
 */
class FaceModelsTest {
    private val golden: Map<String, FloatArray> = ModelTestSupport.resource("face-golden.txt").lines()
        .filter { it.isNotBlank() }
        .associate { line -> line.substringBefore(' ') to ModelTestSupport.floats(line.substringAfter(' ')) }

    @Test
    fun theDetectorFindsTheAstronautsFaceWhereOpenCvDoes() {
        val faces = detector.detect(ModelTestSupport.fullPhoto("astronaut"))

        assertEquals(1, faces.size)
        val face = faces.single()
        val box = golden.getValue("box")
        assertTrue("left ${face.left} vs ${box[0]}", abs(face.left - box[0]) < 1f)
        assertTrue("top ${face.top} vs ${box[1]}", abs(face.top - box[1]) < 1f)
        assertTrue("width ${face.width} vs ${box[2]}", abs(face.width - box[2]) < 1f)
        assertTrue("height ${face.height} vs ${box[3]}", abs(face.height - box[3]) < 1f)
        val expectedPoints = golden.getValue("landmarks")
        for (i in 0 until 10) assertTrue("landmark $i: ${face.landmarks[i]} vs ${expectedPoints[i]}", abs(face.landmarks[i] - expectedPoints[i]) < 1f)
        assertEquals(golden.getValue("score")[0], face.score, 0.01f)
    }

    @Test
    fun aFaceThatFillsTheFrameIsFoundToo() {
        // YuNet alone stops seeing faces beyond ~300 px; here the face is about 450 px across.
        val big = enlarge(ModelTestSupport.fullPhoto("astronaut"), 8)

        val faces = detector.detect(big)

        assertEquals(1, faces.size)
        val box = golden.getValue("box")
        assertTrue("left ${faces.single().left} vs ${box[0] * 8}", abs(faces.single().left - box[0] * 8) < 40f)
        assertTrue("width ${faces.single().width} vs ${box[2] * 8}", abs(faces.single().width - box[2] * 8) < 60f)
        assertTrue(faces.single().score >= 0.8f)
    }

    @Test
    fun aPhotoWithoutAFaceHasNone() {
        for (name in listOf("cat", "coffee", "rocket")) assertTrue("$name", detector.detect(ModelTestSupport.fullPhoto(name)).isEmpty())
    }

    @Test
    fun ourAlignedCropIsCloseToOpenCvsAndGivesTheSameVector() {
        val photo = ModelTestSupport.fullPhoto("astronaut")
        val face = detector.detect(photo).single()

        val ours = FaceAligner.crop(photo, face.landmarks)
        val reference = png("astronaut-face-112")

        assertTrue("mean pixel difference ${meanDifference(ours, reference)}", meanDifference(ours, reference) < 3.0)
        val expected = golden.getValue("embedding")
        val fromOurs = embedder.embed(ours)
        val fromReference = embedder.embed(reference)
        assertTrue("cosine ${ModelTestSupport.cosine(fromOurs, expected)}", ModelTestSupport.cosine(fromOurs, expected) > 0.995f)
        assertTrue("cosine ${ModelTestSupport.cosine(fromReference, expected)}", ModelTestSupport.cosine(fromReference, expected) > 0.9995f)
    }

    @Test
    fun theSameFaceTwiceIsTheSamePersonAndADifferentThingIsNot() {
        val photo = ModelTestSupport.fullPhoto("astronaut")
        val face = detector.detect(photo).single()
        val vector = embedder.embed(FaceAligner.crop(photo, face.landmarks))
        val flipped = embedder.embed(FaceAligner.crop(photo, face.landmarks).let(::mirror))

        assertEquals(1f, ModelTestSupport.cosine(vector, vector), 1e-4f)
        assertTrue("a mirrored face should still look like the same person: ${ModelTestSupport.cosine(vector, flipped)}", ModelTestSupport.cosine(vector, flipped) > 0.5f)
        val cat = embedder.embed(FaceAligner.crop(ModelTestSupport.fullPhoto("cat"), floatArrayOf(100f, 90f, 160f, 90f, 130f, 120f, 105f, 150f, 155f, 150f)))
        assertTrue("a cat must not look like her: ${ModelTestSupport.cosine(vector, cat)}", ModelTestSupport.cosine(vector, cat) < FaceClustering.DEFAULT_THRESHOLD)
    }

    private fun enlarge(image: RgbImage, factor: Int): RgbImage {
        val source = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        source.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        val scaled = java.awt.image.BufferedImage(image.width * factor, image.height * factor, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.drawImage(source, 0, 0, scaled.width, scaled.height, null)
        graphics.dispose()
        return RgbImage(scaled.width, scaled.height, scaled.getRGB(0, 0, scaled.width, scaled.height, null, 0, scaled.width))
    }

    private fun mirror(image: RgbImage): RgbImage {
        val out = IntArray(image.pixels.size)
        for (y in 0 until image.height) for (x in 0 until image.width) out[y * image.width + x] = image.pixels[y * image.width + (image.width - 1 - x)]
        return RgbImage(image.width, image.height, out)
    }

    private fun png(name: String): RgbImage {
        val image = ImageIO.read(FaceModelsTest::class.java.getResourceAsStream("/embedding/$name.png"))
        return RgbImage(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))
    }

    private fun meanDifference(a: RgbImage, b: RgbImage): Double {
        var sum = 0L
        for (i in a.pixels.indices) {
            for (shift in intArrayOf(0, 8, 16)) sum += abs(((a.pixels[i] shr shift) and 0xFF) - ((b.pixels[i] shr shift) and 0xFF))
        }
        return sum.toDouble() / (a.pixels.size * 3)
    }

    private companion object {
        lateinit var detector: YuNetFaceDetector
        lateinit var embedder: SFaceEmbedder

        @JvmStatic
        @BeforeClass
        fun load() {
            val store = ModelTestSupport.store()
            detector = YuNetFaceDetector(store.map(FACE_DETECTOR_MODEL))
            embedder = SFaceEmbedder(store.map(FACE_EMBEDDER_MODEL))
        }

        @JvmStatic
        @AfterClass
        fun unload() {
            detector.close()
            embedder.close()
        }
    }
}
