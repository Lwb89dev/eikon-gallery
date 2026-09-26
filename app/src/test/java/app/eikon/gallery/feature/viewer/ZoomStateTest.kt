package app.eikon.gallery.feature.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomStateTest {
    private fun state(aspect: Float = 1.5f) = ZoomState().apply {
        containerSize = IntSize(1080, 2000)
        imageAspect = aspect
    }

    @Test
    fun aPhotoIsNotZoomedUntilItIsScaledPastAHairsBreadth() {
        val state = state()
        assertFalse(state.isZoomed)
        state.transform(Offset.Zero, Offset.Zero, 1.01f)
        assertFalse("a touch of scale from a wobbling pinch is not a zoom", state.isZoomed)
        state.transform(Offset.Zero, Offset.Zero, 1.5f)
        assertTrue(state.isZoomed)
    }

    @Test
    fun resettingPutsThePhotoBackAtRest() {
        val state = state()
        state.transform(Offset(100f, 50f), Offset(20f, 0f), 3f)
        assertTrue(state.isZoomed)

        state.reset()

        assertFalse(state.isZoomed)
        assertEquals(1f, state.scale, 0f)
        assertEquals(Offset.Zero, state.offset)
    }

    @Test
    fun zoomingStopsAtTheLimits() {
        val state = state()
        state.transform(Offset.Zero, Offset.Zero, 100f)
        assertEquals(6f, state.scale, 0f)
        state.transform(Offset.Zero, Offset.Zero, 0.001f)
        assertEquals(1f, state.scale, 0f)
        assertFalse(state.isZoomed)
    }

    @Test
    fun aVideoThatIsWiderThanTheScreenCanOnlyBeDraggedAsFarAsItsOwnEdges() {
        // 16:9 in a 1080 x 2000 window is 1080 x 607 on screen; at 2x it is 2160 wide, so it can move 540 either way, and 607 - 2000 < 0 so not at all up or down.
        val state = state(aspect = 16f / 9f)
        state.transform(Offset.Zero, Offset.Zero, 2f)
        state.transform(Offset.Zero, Offset(10_000f, 10_000f), 1f)
        assertEquals(540f, state.offset.x, 0.5f)
        assertEquals(0f, state.offset.y, 0.5f)
    }

    @Test
    fun aPanThatCannotMoveThePhotoIsLeftForThePagerToTake() {
        val state = state(aspect = 16f / 9f)
        state.transform(Offset.Zero, Offset.Zero, 2f)
        state.transform(Offset.Zero, Offset(10_000f, 0f), 1f)
        val movedNow = state.transform(Offset.Zero, Offset(50f, 0f), 1f)
        assertFalse("already against the edge: the swipe goes on to the pager", movedNow)
    }
}
