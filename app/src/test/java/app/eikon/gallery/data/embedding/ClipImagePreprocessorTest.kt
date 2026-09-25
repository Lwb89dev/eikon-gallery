package app.eikon.gallery.data.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ClipImagePreprocessorTest {
    @Test
    fun decodesSoTheShorterSideIs224() {
        assertEquals(224 to 224, ClipImagePreprocessor.decodeSize(4000, 4000))
        assertEquals(299 to 224, ClipImagePreprocessor.decodeSize(4000, 3000))
        assertEquals(224 to 299, ClipImagePreprocessor.decodeSize(3000, 4000))
        assertEquals(224 to 398, ClipImagePreprocessor.decodeSize(1080, 1920))
    }

    @Test
    fun aSmallImageIsNeverDecodedBelowTheModelSize() {
        assertEquals(224 to 224, ClipImagePreprocessor.decodeSize(100, 100))
        assertEquals(448 to 224, ClipImagePreprocessor.decodeSize(100, 50))
    }

    @Test
    fun cropTakesTheCentralSquare() {
        val pixels = IntArray(6 * 4) { it }
        val cropped = RgbImage(6, 4, pixels).centerCrop(4)
        assertEquals(4, cropped.width)
        assertEquals(listOf(1, 2, 3, 4, 7, 8, 9, 10, 13, 14, 15, 16, 19, 20, 21, 22), cropped.pixels.toList())
    }

    @Test
    fun halvingAveragesEachTwoByTwoBlock() {
        val pixels = intArrayOf(
            0xFF000000.toInt(), 0xFF0A0A0A.toInt(), 0xFF141414.toInt(), 0xFF141414.toInt(),
            0xFF141414.toInt(), 0xFF1E1E1E.toInt(), 0xFF141414.toInt(), 0xFF141414.toInt(),
        )
        val half = RgbImage(4, 2, pixels).halved()
        assertEquals(2, half.width)
        assertEquals(1, half.height)
        assertEquals(0xFF0F0F0F.toInt(), half.pixels[0]) // (0 + 10 + 20 + 30) / 4 = 15
        assertEquals(0xFF141414.toInt(), half.pixels[1])
    }

    @Test
    fun cropOfAnAlreadySquareImageReturnsIt() {
        val image = RgbImage(2, 2, IntArray(4))
        assertSame(image, image.centerCrop(2))
    }

    @Test
    fun tensorIsChannelFirstNormalisedWithClipStatistics() {
        val size = ClipImagePreprocessor.SIZE
        val pixels = IntArray(size * size) { 0xFF000000.toInt() or (255 shl 16) or (128 shl 8) or 0 }
        val tensor = ClipImagePreprocessor.toTensor(RgbImage(size, size, pixels))

        val plane = size * size
        assertEquals(3 * plane, tensor.size)
        assertEquals((1f - 0.48145466f) / 0.26862954f, tensor[0], 1e-5f)
        assertEquals((128 / 255f - 0.4578275f) / 0.26130258f, tensor[plane], 1e-5f)
        assertEquals((0f - 0.40821073f) / 0.27577711f, tensor[2 * plane + 5], 1e-5f)
    }

    @Test
    fun tensorRejectsAWrongSize() {
        assertThrows(IllegalArgumentException::class.java) { ClipImagePreprocessor.toTensor(RgbImage(10, 10, IntArray(100))) }
    }
}
