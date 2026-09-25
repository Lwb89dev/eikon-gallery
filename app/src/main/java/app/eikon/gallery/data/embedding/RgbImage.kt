package app.eikon.gallery.data.embedding

/** Decoded pixels, one packed ARGB int each, row by row. Plain data so the image maths runs in JVM tests. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "pixel count does not match $width x $height" }
    }

    /** The image at half the width and height, each new pixel the average of the 2 x 2 it replaces. */
    fun halved(): RgbImage {
        val w = maxOf(1, width / 2)
        val h = maxOf(1, height / 2)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) out[y * w + x] = average(x * 2, y * 2)
        }
        return RgbImage(w, h, out)
    }

    private fun average(x: Int, y: Int): Int {
        var red = 0
        var green = 0
        var blue = 0
        for (dy in 0..1) {
            for (dx in 0..1) {
                val pixel = pixels[minOf(y + dy, height - 1) * width + minOf(x + dx, width - 1)]
                red += (pixel shr RED_SHIFT) and 0xFF
                green += (pixel shr GREEN_SHIFT) and 0xFF
                blue += pixel and 0xFF
            }
        }
        return (0xFF shl ALPHA_SHIFT) or ((red + 2) / 4 shl RED_SHIFT) or ((green + 2) / 4 shl GREEN_SHIFT) or ((blue + 2) / 4)
    }

    /** The central [size] x [size] square. The image must be at least that big on both sides. */
    fun centerCrop(size: Int): RgbImage {
        require(width >= size && height >= size) { "image $width x $height is smaller than $size" }
        if (width == size && height == size) return this
        val left = (width - size) / 2
        val top = (height - size) / 2
        val out = IntArray(size * size)
        for (row in 0 until size) System.arraycopy(pixels, (top + row) * width + left, out, row * size, size)
        return RgbImage(size, size, out)
    }

    private companion object {
        const val RED_SHIFT = 16
        const val GREEN_SHIFT = 8
        const val ALPHA_SHIFT = 24
    }
}
