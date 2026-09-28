package app.eikon.gallery.domain.edit

import app.eikon.gallery.domain.edit.EditTestImages.argb
import app.eikon.gallery.domain.edit.EditTestImages.blue
import app.eikon.gallery.domain.edit.EditTestImages.green
import app.eikon.gallery.domain.edit.EditTestImages.red
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every grid point of the cube must read exactly what [ColorPipeline] itself says for that color: the cube is only worth using because it agrees with it. */
class VideoColorLutTest {
    private fun applyDirectly(color: Int, adjustments: Adjustments = Adjustments.NONE, filter: EditFilter = EditFilter.NONE, amount: Float = 1f): Int {
        val pixels = intArrayOf(color)
        ColorPipeline(adjustments, filter, amount).apply(pixels, 0, 1)
        return pixels[0]
    }

    @Test
    fun everyCornerOfTheCubeMatchesThePipelineDirectly() {
        val adjustments = Adjustments(exposure = 0.4f, contrast = 0.3f, saturation = -0.5f, temperature = 0.2f)
        val size = 5
        val cube = VideoColorLut.cube(adjustments, EditFilter.NONE, 1f, size)
        val corners = listOf(0, size - 1)
        for (ri in corners) for (gi in corners) for (bi in corners) {
            val level = { i: Int -> i * 255 / (size - 1) }
            val input = argb(level(ri), level(gi), level(bi))
            assertEquals(applyDirectly(input, adjustments), cube[ri][gi][bi])
        }
    }

    @Test
    fun aFilterIsBakedIntoTheCubeJustAsItIsForAPhoto() {
        val size = 9
        val level = { i: Int -> i * 255 / (size - 1) }
        val cube = VideoColorLut.cube(Adjustments.NONE, EditFilter.SEPIA, 0.7f, size)
        val ri = 6; val gi = 2; val bi = 1
        val gridColor = argb(level(ri), level(gi), level(bi))
        assertEquals(applyDirectly(gridColor, filter = EditFilter.SEPIA, amount = 0.7f), cube[ri][gi][bi])
    }

    @Test
    fun withNoAdjustmentEveryGridPointIsUntouched() {
        val cube = VideoColorLut.cube(Adjustments.NONE, EditFilter.NONE, 1f, size = 4)
        val level = { i: Int -> i * 255 / 3 }
        for (r in 0..3) for (g in 0..3) for (b in 0..3) {
            val color = argb(level(r), level(g), level(b))
            assertEquals(color, cube[r][g][b])
        }
    }

    @Test
    fun fullyDesaturatingMakesEveryGridPointGray() {
        val cube = VideoColorLut.cube(Adjustments(saturation = -1f), EditFilter.NONE, 1f, size = VideoColorLut.DEFAULT_SIZE)
        val color = cube[3][1][2]
        assertTrue("a fully desaturated color has equal channels", red(color) == green(color) && green(color) == blue(color))
    }

    @Test
    fun theDefaultCubeIsAThousandOrSoEntriesNotAWholeImage() {
        val cube = VideoColorLut.cube(Adjustments.NONE, EditFilter.NONE, 1f)
        assertEquals(VideoColorLut.DEFAULT_SIZE, cube.size)
        assertEquals(VideoColorLut.DEFAULT_SIZE, cube[0].size)
        assertEquals(VideoColorLut.DEFAULT_SIZE, cube[0][0].size)
    }
}
