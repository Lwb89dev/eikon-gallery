package app.eikon.gallery.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailSizesTest {
    @Test
    fun aCellIsAskedForTheSmallestSizeThatCoversIt() {
        assertEquals(192, ThumbnailSizes.forCell(154))
        assertEquals(192, ThumbnailSizes.forCell(192))
        assertEquals(384, ThumbnailSizes.forCell(193))
        assertEquals(576, ThumbnailSizes.forCell(538))
    }

    @Test
    fun neighbouringColumnCountsOfAPhoneShareASize() {
        // A 1080 px wide screen with the 1.5 dp gap of the grid: 4 and 3 columns are served by the same picture, and so are 7 and 6.
        fun edge(columns: Int) = ThumbnailSizes.forCell((1080 - 4 * (columns - 1)) / columns)
        assertEquals(edge(4), edge(3))
        assertEquals(edge(5), edge(4))
        assertEquals(edge(7), edge(6))
    }

    @Test
    fun aBigScreenWithFewColumnsGetsAFittingSizeNotAnUpscaledOne() {
        val size = ThumbnailSizes.forCell(1000)
        assertTrue("size=$size", size >= 1000)
        assertEquals(0, size % 256)
    }

    @Test
    fun theLadderOnlyGoesUp() {
        assertEquals(ThumbnailSizes.LADDER.sorted(), ThumbnailSizes.LADDER.toList())
    }
}
