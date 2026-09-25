package app.eikon.gallery.feature.library

import kotlin.math.max
import kotlin.math.min

/** A rectangle in pixels, in the coordinates of the screen that the grid and the viewer share. */
data class Frame(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * The arithmetic of the picture that flies between its cell in the grid and its place in the viewer, apart from the screen so it can be tested.
 * The picture is drawn once, at the size it has in the viewer, and shown through a window that goes from the cell to that size: the picture is
 * scaled so that it always *covers* the window (as a grid cell does) and whatever sticks out is cut off. At the start it therefore looks exactly
 * like the cell, at the end exactly like the viewer, and in between there is no stretching.
 */
object HeroGeometry {
    /** Where a picture of [aspect] (width over height) sits when it is fitted and centred in a screen of [width] x [height]: where the viewer draws it at rest. */
    fun fitted(aspect: Float, width: Float, height: Float): Frame {
        if (aspect <= 0f || width <= 0f || height <= 0f) return Frame(0f, 0f, max(width, 0f), max(height, 0f))
        val fitWidth = min(width, height * aspect)
        val fitHeight = fitWidth / aspect
        return Frame((width - fitWidth) / 2, (height - fitHeight) / 2, fitWidth, fitHeight)
    }

    /** The window at [progress] (0 at [from], 1 at [to]). */
    fun between(from: Frame, to: Frame, progress: Float): Frame {
        val p = progress.coerceIn(0f, 1f)
        return Frame(lerp(from.left, to.left, p), lerp(from.top, to.top, p), lerp(from.width, to.width, p), lerp(from.height, to.height, p))
    }

    /** How much a picture laid out at [content] size must be scaled to cover [window] completely. */
    fun coverScale(window: Frame, content: Frame): Float {
        if (content.width <= 0f || content.height <= 0f) return 1f
        return max(window.width / content.width, window.height / content.height)
    }

    private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
}
