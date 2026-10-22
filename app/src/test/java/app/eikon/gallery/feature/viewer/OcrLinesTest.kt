package app.eikon.gallery.feature.viewer

import app.eikon.gallery.data.ocr.OcrWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the OCR words become the selectable lines of the viewer's text layer: one per line, so a copied sentence keeps its spaces. */
class OcrLinesTest {
    private fun word(text: String, line: Int, left: Float, top: Float, right: Float, bottom: Float) = OcrWord(text, line, left, top, right, bottom)

    @Test
    fun wordsOfOneLineAreJoinedBySpacesInTheirOwnOrder() {
        val lines = ocrLines(
            listOf(
                word("Totale", 0, 0.10f, 0.50f, 0.30f, 0.55f),
                word("12,50", 0, 0.60f, 0.50f, 0.80f, 0.55f),
                word("EUR", 0, 0.82f, 0.50f, 0.90f, 0.55f),
            ),
        )

        assertEquals(listOf("Totale 12,50 EUR"), lines.map { it.text })
    }

    @Test
    fun aLineIsBoxedByTheUnionOfItsWordsEvenWhenTheyDoNotSitLevel() {
        val line = ocrLines(listOf(word("a", 3, 0.20f, 0.41f, 0.30f, 0.50f), word("b", 3, 0.35f, 0.40f, 0.50f, 0.52f))).single()

        assertEquals(0.20f, line.left, 0f)
        assertEquals(0.40f, line.top, 0f)
        assertEquals(0.50f, line.right, 0f)
        assertEquals(0.52f, line.bottom, 0f)
    }

    @Test
    fun linesComeInReadingOrderWhateverOrderTheWordsArrivedIn() {
        val lines = ocrLines(listOf(word("second", 1, 0f, 0.2f, 0.5f, 0.3f), word("first", 0, 0f, 0.1f, 0.5f, 0.2f), word("third", 2, 0f, 0.3f, 0.5f, 0.4f)))

        assertEquals(listOf("first", "second", "third"), lines.map { it.text })
    }

    @Test
    fun noWordsMeansNoLines() {
        assertTrue(ocrLines(emptyList()).isEmpty())
    }
}
