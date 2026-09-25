package app.eikon.gallery.data.ocr

import android.graphics.Bitmap

/** Text recognized in an image and how sure the engine is about it (0 to 100). */
data class OcrResult(val text: String, val confidence: Int)

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
