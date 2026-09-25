package app.eikon.gallery.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailAspectsTest {
    @Test
    fun remembersTheShapeOfAThumbnail() {
        val aspects = ThumbnailAspects()
        aspects.remember(7, 400, 300)
        assertEquals(4f / 3f, aspects.of(7)!!, 0.0001f)
    }

    @Test
    fun aPhotoNeverSeenHasNoShape() {
        assertNull(ThumbnailAspects().of(1))
    }

    @Test
    fun aThumbnailWithNoSizeIsIgnored() {
        val aspects = ThumbnailAspects()
        aspects.remember(1, 0, 300)
        aspects.remember(2, 300, 0)
        assertNull(aspects.of(1))
        assertNull(aspects.of(2))
    }

    @Test
    fun theLatestThumbnailWinsSoAnEditedPhotoTakesItsNewShape() {
        val aspects = ThumbnailAspects()
        aspects.remember(3, 400, 300)
        aspects.remember(3, 300, 300)
        assertEquals(1f, aspects.of(3)!!, 0f)
    }

    @Test
    fun itIsBoundedAndForgetsTheLeastRecentlyUsedFirst() {
        val aspects = ThumbnailAspects(capacity = 3)
        aspects.remember(1, 1, 1)
        aspects.remember(2, 1, 1)
        aspects.remember(3, 1, 1)
        aspects.of(1) // looked up again: now the most recent
        aspects.remember(4, 1, 1)

        assertNull(aspects.of(2))
        assertEquals(1f, aspects.of(1)!!, 0f)
        assertEquals(1f, aspects.of(4)!!, 0f)
    }
}
