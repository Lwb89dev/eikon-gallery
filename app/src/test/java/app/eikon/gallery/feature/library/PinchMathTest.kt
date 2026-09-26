package app.eikon.gallery.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic behind the grid following the fingers: the scale is continuous, and a change of column count never changes what is on screen. */
class PinchMathTest {
    private val min = 2
    private val max = 7
    private val tolerance = 1e-4f

    /** How big a cell looks: its share of the width times the scale. What the user sees, and what must not jump. */
    private fun apparentCell(state: PinchState) = state.scale / state.columns

    @Test
    fun aSmallPinchOnlyScalesAndKeepsTheColumns() {
        val next = PinchMath.zoom(PinchState(4), 1.1f, min, max)
        assertEquals(4, next.columns)
        assertEquals(1.1f, next.scale, tolerance)
    }

    @Test
    fun spreadingTheFingersFarEnoughGivesFewerColumnsWithoutChangingWhatIsSeen() {
        var state = PinchState(4)
        var before = apparentCell(state)
        repeat(30) {
            state = PinchMath.zoom(state, 1.02f, min, max)
            // Every step of the pinch makes the cells look a little bigger and never smaller, including at the moment the columns change.
            assertTrue(apparentCell(state) >= before - tolerance)
            before = apparentCell(state)
        }
        assertTrue("columns=${state.columns}", state.columns < 4)
    }

    @Test
    fun theSwitchOfColumnsItselfLeavesTheApparentSizeUnchanged() {
        val before = PinchState(4, 1.36f)
        val after = PinchMath.zoom(before, 1.05f, min, max)
        assertEquals(3, after.columns)
        assertEquals(apparentCell(PinchState(4, 1.36f * 1.05f)), apparentCell(after), tolerance)
    }

    @Test
    fun bringingTheFingersTogetherGivesMoreColumnsTheSameWay() {
        val before = PinchState(4, 0.8f)
        val after = PinchMath.zoom(before, 0.95f, min, max)
        assertEquals(5, after.columns)
        assertEquals(apparentCell(PinchState(4, 0.8f * 0.95f)), apparentCell(after), tolerance)
    }

    @Test
    fun aFastPinchCanCrossSeveralLayoutsInOneMove() {
        val next = PinchMath.zoom(PinchState(6), 2.6f, min, max)
        assertTrue("columns=${next.columns}", next.columns <= 3)
        assertEquals(apparentCell(PinchState(6, 2.6f)), apparentCell(next), tolerance)
    }

    @Test
    fun theColumnsStayWithinTheirLimitsAndTheScaleCannotBeDraggedFarPastThem() {
        val zoomedIn = PinchMath.zoom(PinchState(2), 5f, min, max)
        assertEquals(2, zoomedIn.columns)
        assertEquals(PinchMath.OVERSCALE, zoomedIn.scale, tolerance)

        val zoomedOut = PinchMath.zoom(PinchState(7), 0.1f, min, max)
        assertEquals(7, zoomedOut.columns)
        assertEquals(1f / PinchMath.OVERSCALE, zoomedOut.scale, tolerance)
    }

    @Test
    fun fingersHoveringOnTheEdgeBetweenTwoLayoutsDoNotMakeItFlipBack() {
        // Spread until the grid has just gone from 4 to 3 columns, then pinch back by a hair: it must not return to 4 at once.
        var state = PinchState(4)
        while (state.columns == 4) state = PinchMath.zoom(state, 1.01f, min, max)
        assertEquals(3, state.columns)
        val nudged = PinchMath.zoom(state, 0.98f, min, max)
        assertEquals(3, nudged.columns)
    }

    @Test
    fun liftingTheFingersPastHalfWayGoesToTheNextLayoutAndLeavesTheRestToAnimate() {
        val past = PinchMath.settle(PinchState(4, 1.2f), min, max)
        assertEquals(3, past.columns)
        assertEquals(apparentCell(PinchState(4, 1.2f)), apparentCell(past), tolerance)
        // What is left to animate to 1 is a shrink, since the cells of the new layout are bigger than they looked.
        assertTrue(past.scale < 1f)
    }

    @Test
    fun liftingTheFingersBeforeHalfWayGoesBack() {
        val short = PinchState(4, 1.1f)
        assertEquals(short, PinchMath.settle(short, min, max))
        val shortOut = PinchState(4, 0.9f)
        assertEquals(shortOut, PinchMath.settle(shortOut, min, max))
    }

    @Test
    fun liftingTheFingersWhileZoomedOutPastHalfWayGoesToMoreColumns() {
        val past = PinchMath.settle(PinchState(4, 0.85f), min, max)
        assertEquals(5, past.columns)
        assertTrue(past.scale > 1f)
    }

    @Test
    fun theLimitsHoldWhenLiftingTheFingersToo() {
        assertEquals(2, PinchMath.settle(PinchState(2, PinchMath.OVERSCALE), min, max).columns)
        assertEquals(7, PinchMath.settle(PinchState(7, 1f / PinchMath.OVERSCALE), min, max).columns)
    }
}
