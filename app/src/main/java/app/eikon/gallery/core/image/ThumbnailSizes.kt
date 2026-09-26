package app.eikon.gallery.core.image

/**
 * The few sizes grid thumbnails are asked for at. A thumbnail asked for at exactly the size of its cell would have to be loaded again every time a pinch changes the number of columns
 * (each column count has its own cell size), which is what made the grid flicker while it changed density. Asking for the smallest size on this ladder that covers the cell keeps one
 * picture good for several column counts, and where it does have to change, the picture already on screen stays until the new one is there.
 */
object ThumbnailSizes {
    /** Edge in pixels of the square thumbnails, smallest first. */
    val LADDER = intArrayOf(192, 384, 576, 768)

    /** Beyond the ladder (a big screen with few columns) sizes are rounded up to a multiple of this, which keeps them few too. */
    private const val BIG_STEP = 256

    /** The size to ask for a cell whose edge is [cellEdgePx] pixels. */
    fun forCell(cellEdgePx: Int): Int = LADDER.firstOrNull { it >= cellEdgePx } ?: ((cellEdgePx + BIG_STEP - 1) / BIG_STEP * BIG_STEP)
}
