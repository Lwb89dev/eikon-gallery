package app.eikon.gallery.domain

import java.nio.charset.StandardCharsets

/**
 * Finds the embedded clip of a "Motion Photo" (Pixel, Samsung and other cameras that save a few seconds of video inside a JPEG): where, in
 * bytes, it starts. The clip is simply an MP4 appended after the picture; a player is handed the file with that many bytes skipped and plays
 * it like any other video. Nothing here reads or copies a file: it only looks at bytes already read elsewhere (see [findVideoOffset]).
 *
 * Two schemas are understood, both written into the JPEG's XMP packet (an XML block near the start of the file, same place camera and GPS
 * data live), so [header] only needs the first part of the file, not the whole photo:
 *
 * - **Container Directory** (Pixel since 2020; adopted by many other cameras and by Android's own `MediaStore` "motion photo" support): an
 *   ordered list of `Container:Item` elements, each with `Item:Semantic` ("Primary" the still photo, "MotionPhoto" the clip) and `Item:Length`
 *   in bytes; items are laid out in the file in that order. The clip's offset is the file's length minus its own length and that of whatever
 *   the spec allows after it (in practice nothing, but a padding value is honoured if present).
 * - **MicroVideo** (early Google Camera and Samsung phones): `GCamera:MicroVideoOffset`, the clip's length counted from the *end* of the
 *   file, so its offset is simply the file's length minus that value.
 *
 * A file with neither marker is not a Motion Photo: [findVideoOffset] returns null, cheaply (regexes that do not match cost little), rather
 * than guessing from the bytes themselves. **Not verified against a real Motion Photo file** (both schemas are implemented from their public
 * specifications, not from a sample); a manufacturer's own variation could differ in details this does not cover.
 */
object MotionPhotoDetector {
    /**
     * The byte offset where the clip starts, or null if [header] (the first part of the file; a few hundred KB is enough for the XMP packet
     * of a real photo) shows no sign of one, or the numbers it gives make no sense for a file of [fileSize] bytes.
     */
    fun findVideoOffset(header: ByteArray, fileSize: Long): Long? {
        val text = String(header, StandardCharsets.ISO_8859_1) // byte-for-byte: every value 0..255 round-trips to one char, so the regexes below see the raw bytes as text
        return containerDirectoryOffset(text, fileSize) ?: microVideoOffset(text, fileSize)
    }

    private fun sane(offset: Long, fileSize: Long): Long? = offset.takeIf { it in 0 until fileSize }

    // --- Container Directory (Pixel "Motion Photo") -------------------------------------------------

    private val ITEM_TAG = Regex("<Container:Item\\b([^>]*?)/?>")
    private val SEMANTIC = Regex("Item:Semantic=\"(\\w+)\"")
    private val LENGTH = Regex("Item:Length=\"(\\d+)\"")
    private val PADDING = Regex("Item:Padding=\"(\\d+)\"")

    /** The sum of the lengths (and any padding) of the clip's own item and every item the directory lists after it, which is how far back from the end of the file it starts. */
    private fun containerDirectoryOffset(text: String, fileSize: Long): Long? {
        val items = ITEM_TAG.findAll(text).map { it.groupValues[1] }.toList()
        val motionAt = items.indexOfFirst { SEMANTIC.find(it)?.groupValues?.get(1) == "MotionPhoto" }
        if (motionAt < 0) return null
        val trailingBytes = items.drop(motionAt).sumOf { attribute(it) }
        return sane(fileSize - trailingBytes, fileSize)
    }

    private fun attribute(item: String): Long {
        val length = LENGTH.find(item)?.groupValues?.get(1)?.toLongOrNull() ?: return 0L
        val padding = PADDING.find(item)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return length + padding
    }

    // --- MicroVideo (early Google Camera / Samsung) --------------------------------------------------

    private val MICRO_VIDEO_OFFSET = Regex("GCamera:MicroVideoOffset=\"(\\d+)\"")

    private fun microVideoOffset(text: String, fileSize: Long): Long? {
        val fromEnd = MICRO_VIDEO_OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return sane(fileSize - fromEnd, fileSize)
    }
}
