package app.eikon.gallery.data.mediastore

/** Category flags stored on each index row so library filters are plain column comparisons. */
data class MediaCategories(
    val isScreenshot: Boolean,
    val isScreenRecording: Boolean,
    val isPanorama: Boolean,
    val isRaw: Boolean,
)

/**
 * Heuristics that decide which special category a MediaStore item belongs to.
 *
 * Android exposes no reliable "this is a screenshot / panorama" flag, so detection relies on where
 * the file lives, how it is named and (for panoramas) its shape. It can be wrong for files that were
 * renamed or moved; the UI labels these filters accordingly. Selfie and "edited" detection are not
 * attempted here because nothing in MediaStore identifies them.
 */
object MediaClassifier {
    private val RAW_EXTENSIONS = setOf(
        "dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "rw2", "orf", "raf",
        "pef", "srw", "rwl", "raw", "3fr", "erf", "kdc", "mrw", "x3f",
    )

    private const val PANORAMA_MIN_RATIO = 2.5
    private const val PANORAMA_MIN_LONG_SIDE = 3000
    private const val PANORAMA_MIN_SHORT_SIDE = 1000

    fun classify(
        isVideo: Boolean,
        mimeType: String,
        displayName: String,
        relativePath: String?,
        bucketName: String?,
        width: Int,
        height: Int,
    ): MediaCategories {
        val hints = listOf(displayName, relativePath.orEmpty(), bucketName.orEmpty()).map(::squash)
        val screenshot = !isVideo && hints.any { "screenshot" in it || "schermat" in it }
        return MediaCategories(
            isScreenshot = screenshot,
            isScreenRecording = isVideo && hints.any(::looksLikeScreenRecording),
            isPanorama = !isVideo && !screenshot && looksLikePanorama(displayName, width, height),
            isRaw = !isVideo && looksRaw(mimeType, displayName),
        )
    }

    /** Lowercase letters and digits only, so "Screen recordings" and "Screen_Recording" compare alike. */
    private fun squash(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun looksLikeScreenRecording(squashed: String): Boolean =
        "screenrecord" in squashed ||
            "registrazioneschermo" in squashed ||
            "registrazionidischermo" in squashed

    private fun looksLikePanorama(displayName: String, width: Int, height: Int): Boolean {
        if ("pano" in squash(displayName)) return true
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        if (shortSide < PANORAMA_MIN_SHORT_SIDE || longSide < PANORAMA_MIN_LONG_SIDE) return false
        return longSide.toDouble() / shortSide >= PANORAMA_MIN_RATIO
    }

    private fun looksRaw(mimeType: String, displayName: String): Boolean {
        val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        if (extension in RAW_EXTENSIONS) return true
        return mimeType.lowercase().let { "dng" in it || "raw" in it || "x-canon-cr" in it || "x-nikon-n" in it }
    }
}
