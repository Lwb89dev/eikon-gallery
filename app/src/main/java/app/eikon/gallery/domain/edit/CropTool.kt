package app.eikon.gallery.domain.edit

import kotlin.math.abs
import kotlin.math.min

/** The parts of the crop rectangle a finger can grab. */
enum class CropHandle { MOVE, TOP_LEFT, TOP, TOP_RIGHT, RIGHT, BOTTOM_RIGHT, BOTTOM, BOTTOM_LEFT, LEFT }

/** The shapes the crop can be locked to. [ratio] is width over height; null leaves it free, and [ORIGINAL] keeps the picture's own shape. */
enum class CropShape(val ratio: Float?) {
    FREE(null), ORIGINAL(-1f), SQUARE(1f), FOUR_THREE(4f / 3f), THREE_TWO(3f / 2f), SIXTEEN_NINE(16f / 9f),
}

/**
 * The arithmetic of dragging the crop rectangle, apart from the screen so it can be tested. The crop is in fractions of the picture (0..1 each
 * way); [canvasAspect] is the picture's width over height in pixels, needed because a square crop is not a square in fractions unless the
 * picture is. Whatever is dragged, the crop stays inside the picture and never gets smaller than [Crop.MIN_SIZE].
 */
object CropTool {
    /** The handle under ([x], [y]) (fractions of the picture) within [slop] of it, corners before edges before the inside; null outside. */
    fun handleAt(crop: Crop, x: Float, y: Float, slop: Float): CropHandle? {
        val nearLeft = abs(x - crop.left) <= slop
        val nearRight = abs(x - crop.right) <= slop
        val nearTop = abs(y - crop.top) <= slop
        val nearBottom = abs(y - crop.bottom) <= slop
        val insideX = x >= crop.left - slop && x <= crop.right + slop
        val insideY = y >= crop.top - slop && y <= crop.bottom + slop
        return when {
            nearLeft && nearTop -> CropHandle.TOP_LEFT
            nearRight && nearTop -> CropHandle.TOP_RIGHT
            nearLeft && nearBottom -> CropHandle.BOTTOM_LEFT
            nearRight && nearBottom -> CropHandle.BOTTOM_RIGHT
            nearTop && insideX -> CropHandle.TOP
            nearBottom && insideX -> CropHandle.BOTTOM
            nearLeft && insideY -> CropHandle.LEFT
            nearRight && insideY -> CropHandle.RIGHT
            x in crop.left..crop.right && y in crop.top..crop.bottom -> CropHandle.MOVE
            else -> null
        }
    }

    /** The crop after dragging [handle] by ([dx], [dy]) fractions; [ratio] (width over height, in pixels) locks the shape if not null. */
    fun drag(crop: Crop, handle: CropHandle, dx: Float, dy: Float, ratio: Float?, canvasAspect: Float): Crop {
        if (handle == CropHandle.MOVE) return move(crop, dx, dy)
        val free = resize(crop, handle, dx, dy)
        return if (ratio == null) free else lockShape(crop, free, handle, ratio / canvasAspect)
    }

    /** The largest crop of shape [ratio] (width over height in pixels) that fits in the picture, centred on the current crop where it can be. */
    fun fit(crop: Crop, ratio: Float, canvasAspect: Float): Crop {
        val shape = ratio / canvasAspect // width over height in fractions
        var width = crop.right - crop.left
        var height = width / shape
        if (height > 1f) { height = 1f; width = height * shape }
        if (width > 1f) { width = 1f; height = width / shape }
        val centreX = (crop.left + crop.right) / 2
        val centreY = (crop.top + crop.bottom) / 2
        val left = (centreX - width / 2).coerceIn(0f, 1f - width)
        val top = (centreY - height / 2).coerceIn(0f, 1f - height)
        return Crop(left, top, left + width, top + height).clamped()
    }

    /** The whole picture, or for [CropShape.ORIGINAL] the picture's own shape. */
    fun ratioOf(shape: CropShape, canvasAspect: Float): Float? = if (shape == CropShape.ORIGINAL) canvasAspect else shape.ratio

    private fun move(crop: Crop, dx: Float, dy: Float): Crop {
        val width = crop.right - crop.left
        val height = crop.bottom - crop.top
        val left = (crop.left + dx).coerceIn(0f, 1f - width)
        val top = (crop.top + dy).coerceIn(0f, 1f - height)
        return Crop(left, top, left + width, top + height)
    }

    private fun resize(crop: Crop, handle: CropHandle, dx: Float, dy: Float): Crop {
        var left = crop.left
        var top = crop.top
        var right = crop.right
        var bottom = crop.bottom
        if (handle in LEFT_SIDE) left = (left + dx).coerceIn(0f, right - Crop.MIN_SIZE)
        if (handle in RIGHT_SIDE) right = (right + dx).coerceIn(left + Crop.MIN_SIZE, 1f)
        if (handle in TOP_SIDE) top = (top + dy).coerceIn(0f, bottom - Crop.MIN_SIZE)
        if (handle in BOTTOM_SIDE) bottom = (bottom + dy).coerceIn(top + Crop.MIN_SIZE, 1f)
        return Crop(left, top, right, bottom)
    }

    /**
     * Makes [dragged] the shape [shape] (width over height in fractions) while the side opposite the handle stays where it was in [before]:
     * a corner keeps the opposite corner, an edge keeps the opposite edge and the middle of the other two.
     */
    private fun lockShape(before: Crop, dragged: Crop, handle: CropHandle, shape: Float): Crop {
        val anchorX = if (handle in LEFT_SIDE) before.right else if (handle in RIGHT_SIDE) before.left else (before.left + before.right) / 2
        val anchorY = if (handle in TOP_SIDE) before.bottom else if (handle in BOTTOM_SIDE) before.top else (before.top + before.bottom) / 2
        val horizontalRoom = if (handle in LEFT_SIDE) anchorX else if (handle in RIGHT_SIDE) 1f - anchorX else 2 * min(anchorX, 1f - anchorX)
        val verticalRoom = if (handle in TOP_SIDE) anchorY else if (handle in BOTTOM_SIDE) 1f - anchorY else 2 * min(anchorY, 1f - anchorY)
        val wanted = if (handle == CropHandle.TOP || handle == CropHandle.BOTTOM) (dragged.bottom - dragged.top) * shape else dragged.right - dragged.left
        val width = min(min(wanted, horizontalRoom), verticalRoom * shape).coerceAtLeast(Crop.MIN_SIZE)
        val height = width / shape
        val left = if (handle in LEFT_SIDE) anchorX - width else if (handle in RIGHT_SIDE) anchorX else anchorX - width / 2
        val top = if (handle in TOP_SIDE) anchorY - height else if (handle in BOTTOM_SIDE) anchorY else anchorY - height / 2
        return Crop(left, top, left + width, top + height).clamped()
    }

    private val LEFT_SIDE = setOf(CropHandle.TOP_LEFT, CropHandle.LEFT, CropHandle.BOTTOM_LEFT)
    private val RIGHT_SIDE = setOf(CropHandle.TOP_RIGHT, CropHandle.RIGHT, CropHandle.BOTTOM_RIGHT)
    private val TOP_SIDE = setOf(CropHandle.TOP_LEFT, CropHandle.TOP, CropHandle.TOP_RIGHT)
    private val BOTTOM_SIDE = setOf(CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM, CropHandle.BOTTOM_RIGHT)
}
