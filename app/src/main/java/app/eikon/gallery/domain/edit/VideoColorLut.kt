package app.eikon.gallery.domain.edit

/**
 * A 3D color lookup table for the color part of an [EditRecipe] ([Adjustments], the filter and its amount; not the geometry or the trim,
 * which have nothing to do with one pixel's own color). Every one of a coarse grid of possible input colors is run through the exact same
 * [ColorPipeline] used for photos, so a video edited with the same recipe reads the same numbers and looks the same. Built once per edit,
 * then only looked up per pixel, which is what makes a video-length stream of frames affordable: nowhere near real time otherwise (see the
 * Media3 wiring that turns this into a [androidx.media3.effect.SingleColorLut] in `feature/viewer`).
 *
 * The two spatial passes photos also have, sharpening and the vignette, look at more than one pixel's own color and are not part of this: a
 * video edit does not carry them yet.
 */
object VideoColorLut {
    /** [size] steps per channel is enough that no banding shows in a photo; more costs memory and upload time for no visible gain. */
    const val DEFAULT_SIZE = 17

    /**
     * `cube[r][g][b]` is the packed ARGB color (full alpha) that a pixel of that color becomes under [adjustments], [filter] and
     * [filterAmount], indices spaced evenly from 0 to 255.
     */
    fun cube(adjustments: Adjustments, filter: EditFilter, filterAmount: Float, size: Int = DEFAULT_SIZE): Array<Array<IntArray>> {
        val grid = IntArray(size) { it * 255 / (size - 1) }
        val pixels = IntArray(size * size * size)
        var i = 0
        for (r in grid) for (g in grid) for (b in grid) pixels[i++] = pack(r, g, b)
        ColorPipeline(adjustments, filter, filterAmount).apply(pixels, 0, pixels.size)
        i = 0
        return Array(size) { Array(size) { IntArray(size) { pixels[i++] } } }
    }

    private fun pack(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
