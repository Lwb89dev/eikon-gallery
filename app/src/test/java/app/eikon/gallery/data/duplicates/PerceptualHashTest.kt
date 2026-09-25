package app.eikon.gallery.data.duplicates

import app.eikon.gallery.data.embedding.ModelTestSupport
import app.eikon.gallery.data.embedding.RgbImage
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualHashTest {
    private val names = listOf("cat", "astronaut", "coffee", "rocket")

    private fun decode(name: String): BufferedImage =
        ImageIO.read(PerceptualHashTest::class.java.getResourceAsStream("/embedding/photos/$name.jpg"))

    private fun toImage(image: BufferedImage) =
        RgbImage(image.width, image.height, image.getRGB(0, 0, image.width, image.height, null, 0, image.width))

    private fun resized(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.drawImage(image, 0, 0, width, height, null)
        g.dispose()
        return out
    }

    @Test
    fun matchesTheReferenceImplementationWithinAFewBits() {
        val golden = ModelTestSupport.resource("phash-golden.tsv").lines().filter { it.isNotBlank() }.associate { it.substringBefore('\t') to it.substringAfter('\t').toLong() }
        for (name in names) {
            val distance = PerceptualHash.distance(PerceptualHash.of(toImage(decode(name))), golden.getValue(name))
            assertTrue("$name differs from the reference by $distance bits", distance <= 3)
        }
    }

    @Test
    fun theSamePictureAtAnotherSizeIsTheSameFingerprintOrNearly() {
        for (name in names) {
            val original = decode(name)
            val full = PerceptualHash.of(toImage(original))
            for ((w, h) in listOf(original.width / 2 to original.height / 2, 64 to 64, original.width * 2 to original.height * 2)) {
                val distance = PerceptualHash.distance(full, PerceptualHash.of(toImage(resized(original, w, h))))
                assertTrue("$name at ${w}x$h: $distance bits", distance <= PerceptualHash.DUPLICATE_DISTANCE)
            }
        }
    }

    @Test
    fun differentPicturesAreFarApart() {
        val hashes = names.map { PerceptualHash.of(toImage(decode(it))) }
        for (a in hashes.indices) for (b in a + 1 until hashes.size) {
            val distance = PerceptualHash.distance(hashes[a], hashes[b])
            assertTrue("${names[a]} vs ${names[b]}: only $distance bits apart", distance > 14)
        }
    }

    @Test
    fun brightnessAndSmallRecompressionBarelyMoveIt() {
        val original = decode("cat")
        val brighter = BufferedImage(original.width, original.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until original.height) for (x in 0 until original.width) {
            val p = original.getRGB(x, y)
            fun c(shift: Int) = (((p shr shift) and 0xFF) * 1.15 + 8).toInt().coerceIn(0, 255)
            brighter.setRGB(x, y, (c(16) shl 16) or (c(8) shl 8) or c(0))
        }
        assertTrue(PerceptualHash.distance(PerceptualHash.of(toImage(original)), PerceptualHash.of(toImage(brighter))) <= PerceptualHash.DUPLICATE_DISTANCE)
    }

    @Test
    fun aFlatPictureHasAFingerprintWithoutCrashing() {
        val flat = RgbImage(50, 40, IntArray(2000) { 0xFF808080.toInt() })
        assertEquals(PerceptualHash.of(flat), PerceptualHash.of(flat))
    }

    @Test
    fun theDistanceCountsDifferingBits() {
        assertEquals(0, PerceptualHash.distance(0x0F0F, 0x0F0F))
        assertEquals(4, PerceptualHash.distance(0b1111, 0))
        assertEquals(64, PerceptualHash.distance(0L, -1L))
    }
}
