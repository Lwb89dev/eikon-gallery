package app.eikon.gallery.feature.library

import kotlin.math.sqrt

/**
 * Where a pinch on the grid stands: the number of [columns] the grid is laid out with, and how far the picture of it is scaled on top of that ([scale], 1 = exactly as laid out).
 * While the fingers move only the scale changes, which costs nothing (it is a layer transform); when the cells have grown or shrunk by as much as one more or one fewer column
 * would make them, the layout takes that column count and the scale is divided by the same factor, so what is on screen does not change at that moment.
 */
data class PinchState(val columns: Int, val scale: Float = 1f)

/** The arithmetic of that, kept apart from the gestures and the drawing so it can be tested on its own. */
object PinchMath {
    /** How far past the densest or the sparsest layout the picture can be pulled, as a factor. It springs back when the fingers lift. */
    const val OVERSCALE = 1.15f

    /** A step is taken a little past its exact factor, so fingers hovering on the edge between two layouts do not make it flip back and forth. */
    private const val HYSTERESIS = 1.04f

    /** By what factor the cells grow when the grid goes from [columns] to one column fewer. */
    fun growth(columns: Int): Float = columns.toFloat() / (columns - 1)

    /** By what factor the cells shrink when the grid goes from [columns] to one column more. */
    fun shrink(columns: Int): Float = columns.toFloat() / (columns + 1)

    /** The state after the fingers changed the distance between them by [factor] (above 1 is spreading them: bigger cells, fewer columns). */
    fun zoom(state: PinchState, factor: Float, minColumns: Int, maxColumns: Int): PinchState {
        var columns = state.columns
        var scale = state.scale * factor
        while (columns > minColumns && scale >= growth(columns) * HYSTERESIS) {
            scale /= growth(columns)
            columns--
        }
        while (columns < maxColumns && scale <= shrink(columns) / HYSTERESIS) {
            scale /= shrink(columns)
            columns++
        }
        val upper = if (columns == minColumns) OVERSCALE else Float.MAX_VALUE
        val lower = if (columns == maxColumns) 1f / OVERSCALE else 0f
        return PinchState(columns, scale.coerceIn(lower, upper))
    }

    /**
     * The fingers lifted: past half way (in the sense of a factor, so the geometric middle) to the next layout the grid goes there, otherwise it stays. Either way the returned
     * scale is what is left to animate back to 1, which the caller does.
     */
    fun settle(state: PinchState, minColumns: Int, maxColumns: Int): PinchState {
        val columns = state.columns
        val scale = state.scale
        return when {
            columns > minColumns && scale >= sqrt(growth(columns)) -> PinchState(columns - 1, scale / growth(columns))
            columns < maxColumns && scale <= sqrt(shrink(columns)) -> PinchState(columns + 1, scale / shrink(columns))
            else -> state
        }
    }
}
