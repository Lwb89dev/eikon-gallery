package app.eikon.gallery.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OcrTextFilterTest {
    @Test
    fun realTextIsKeptAndWhitespaceCollapsed() {
        val text = "IKEA  Milano\n\nTotale   €  49,90\nGrazie per la visita"
        assertEquals("IKEA Milano Totale € 49,90 Grazie per la visita", OcrTextFilter.clean(text, 88))
    }

    @Test
    fun lowConfidenceResultsAreNoise() {
        assertNull(OcrTextFilter.clean("ricevuta totale importo", OcrTextFilter.MIN_CONFIDENCE - 1))
        assertEquals("ricevuta totale importo", OcrTextFilter.clean("ricevuta totale importo", OcrTextFilter.MIN_CONFIDENCE))
    }

    @Test
    fun tooFewRealWordsIsNotText() {
        assertNull(OcrTextFilter.clean("a b", 90))
        assertNull(OcrTextFilter.clean("Roma", 90)) // a single word is usually a false positive
        assertNull(OcrTextFilter.clean("", 90))
        assertNull(OcrTextFilter.clean("  \n ", 90))
    }

    @Test
    fun symbolSoupFromTexturesIsRejected() {
        assertNull(OcrTextFilter.clean("~~ // \\\\ ## abc def -- ** ^^ || ~~~ ,,, ;;;", 80))
    }

    @Test
    fun longTextIsCapped() {
        val long = "parola ".repeat(2_000)
        assertEquals(4_000, OcrTextFilter.clean(long, 90)!!.length)
    }

    @Test
    fun accentedLettersCountAsLetters() {
        assertEquals("città perù università", OcrTextFilter.clean("città perù università", 90))
    }
}
