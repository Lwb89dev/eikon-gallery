package app.eikon.gallery.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroGeometryTest {
    private val screen = 1080f to 2400f

    private fun fitted(aspect: Float) = HeroGeometry.fitted(aspect, screen.first, screen.second)

    @Test
    fun aWidePictureFillsTheWidthAndIsCentredVertically() {
        val frame = fitted(4f / 3f)
        assertEquals(1080f, frame.width, 0.01f)
        assertEquals(810f, frame.height, 0.01f)
        assertEquals(0f, frame.left, 0.01f)
        assertEquals((2400f - 810f) / 2, frame.top, 0.01f)
    }

    @Test
    fun aTallPictureFillsTheHeightAndIsCentredHorizontally() {
        val frame = fitted(1f / 4f)
        assertEquals(2400f, frame.height, 0.01f)
        assertEquals(600f, frame.width, 0.01f)
        assertEquals((1080f - 600f) / 2, frame.left, 0.01f)
        assertEquals(0f, frame.top, 0.01f)
    }

    @Test
    fun aFittedPictureNeverLeavesTheScreenAndKeepsItsShape() {
        for (aspect in listOf(0.2f, 0.5f, 0.75f, 1f, 1.33f, 1.78f, 3f)) {
            val frame = fitted(aspect)
            assertTrue("$aspect: $frame", frame.left >= -0.01f && frame.top >= -0.01f && frame.right <= 1080.01f && frame.bottom <= 2400.01f)
            assertEquals(aspect, frame.width / frame.height, aspect * 0.001f)
        }
    }

    @Test
    fun anUnknownShapeFillsTheScreenInsteadOfDividingByZero() {
        assertEquals(Frame(0f, 0f, 1080f, 2400f), fitted(0f))
        assertEquals(Frame(0f, 0f, 1080f, 2400f), fitted(-1f))
    }

    @Test
    fun theWindowStartsAtTheOneFrameEndsAtTheOtherAndIsHalfWayBetween() {
        val cell = Frame(100f, 200f, 300f, 300f)
        val viewer = Frame(0f, 800f, 1080f, 810f)
        assertEquals(cell, HeroGeometry.between(cell, viewer, 0f))
        assertEquals(viewer, HeroGeometry.between(cell, viewer, 1f))
        val middle = HeroGeometry.between(cell, viewer, 0.5f)
        assertEquals(50f, middle.left, 0.01f)
        assertEquals(500f, middle.top, 0.01f)
        assertEquals(690f, middle.width, 0.01f)
        assertEquals(555f, middle.height, 0.01f)
    }

    @Test
    fun progressOutsideZeroToOneIsHeldAtTheEnds() {
        val a = Frame(0f, 0f, 10f, 10f)
        val b = Frame(100f, 100f, 200f, 200f)
        assertEquals(a, HeroGeometry.between(a, b, -3f))
        assertEquals(b, HeroGeometry.between(a, b, 7f))
    }

    @Test
    fun atTheStartThePictureIsScaledToCoverTheCellLikeACropAndAtTheEndNotAtAll() {
        val picture = fitted(4f / 3f) // 1080 x 810
        val cell = Frame(0f, 0f, 300f, 300f)
        // A square window on a wide picture: the shorter side decides.
        assertEquals(300f / 810f, HeroGeometry.coverScale(cell, picture), 0.0001f)
        assertEquals(1f, HeroGeometry.coverScale(picture, picture), 0.0001f)
    }

    @Test
    fun theScaledPictureAlwaysCoversTheWindowAtEveryStep() {
        val picture = fitted(3f / 4f)
        val cell = Frame(40f, 900f, 350f, 350f)
        for (step in 0..20) {
            val window = HeroGeometry.between(cell, picture, step / 20f)
            val scale = HeroGeometry.coverScale(window, picture)
            assertTrue("step $step", picture.width * scale >= window.width - 0.01f && picture.height * scale >= window.height - 0.01f)
        }
    }

    @Test
    fun aPictureWithNoSizeIsScaledByOneNotByInfinity() {
        assertEquals(1f, HeroGeometry.coverScale(Frame(0f, 0f, 10f, 10f), Frame(0f, 0f, 0f, 0f)), 0f)
    }
}
