package app.eikon.gallery.data.ocr

import android.graphics.Bitmap

/**
 * One recognized word and where it sits, as fractions of the image's own width and height (0 to 1, the same
 * convention a face's box uses) so it does not depend on how large the image was decoded for reading. [line]
 * groups words that sit on the same text line, in reading order, for showing or selecting them where they are
 * actually written on the photo.
 */
data class OcrWord(val text: String, val line: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Text recognized in an image, how sure the engine is about it (0 to 100), and the words it was made of. */
data class OcrResult(val text: String, val confidence: Int, val words: List<OcrWord> = emptyList())

/**
 * A local text recognizer. eikon's implementation is Tesseract (open source, no Google services, no
 * telemetry, models bundled in the app); the interface exists so the engine can be replaced without
 * touching indexing or search. Nothing in an implementation may use the network.
 */
interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): OcrResult

    /** Frees native memory; the engine reinitializes itself on the next [recognize]. */
    fun release()
}

/** Decides whether recognized text is real text worth indexing, or the noise OCR produces on scenery. */
object OcrTextFilter {
    const val MIN_CONFIDENCE = 55
    private const val MIN_WORDS = 2
    private const val MIN_LETTERS_PER_WORD = 3
    private const val MIN_TEXT_RATIO = 0.6
    private const val MAX_CHARS = 4000
    private val whitespace = Regex("\\s+")

    /** The cleaned text to store, or null when the image effectively has no readable text. */
    fun clean(rawText: String, confidence: Int): String? {
        if (confidence < MIN_CONFIDENCE) return null
        val collapsed = rawText.replace(whitespace, " ").trim()
        val words = collapsed.split(' ').filter { word -> word.count { it.isLetter() } >= MIN_LETTERS_PER_WORD }
        if (words.size < MIN_WORDS) return null
        val visible = collapsed.filterNot { it.isWhitespace() }
        val readable = visible.count { it.isLetterOrDigit() }
        if (readable.toDouble() / visible.length < MIN_TEXT_RATIO) return null
        return collapsed.take(MAX_CHARS)
    }
}
