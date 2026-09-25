package app.eikon.gallery.core.image

/**
 * The shape (width over height) of the thumbnails that have been shown, by photo id. A thumbnail always has the shape the photo has when it
 * is looked at (turned upright, and cropped if it has an edit), which the numbers MediaStore keeps do not always say, so whoever needs to
 * know where a photo will sit on the screen before it has loaded (the picture that flies from the grid to the viewer) asks here.
 * Small and bounded: the least recently used entries are forgotten.
 */
class ThumbnailAspects(private val capacity: Int = DEFAULT_CAPACITY) {
    private val shapes = object : LinkedHashMap<Long, Float>(INITIAL, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Float>?) = size > capacity
    }

    fun remember(id: Long, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        synchronized(shapes) { shapes[id] = width.toFloat() / height }
    }

    fun of(id: Long): Float? = synchronized(shapes) { shapes[id] }

    companion object {
        const val DEFAULT_CAPACITY = 512
        private const val INITIAL = 64
        private const val LOAD_FACTOR = 0.75f

        /** The one used by the thumbnail loader and the viewer. */
        val Shared = ThumbnailAspects()
    }
}
