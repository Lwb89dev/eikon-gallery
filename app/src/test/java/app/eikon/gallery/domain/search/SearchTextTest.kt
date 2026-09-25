package app.eikon.gallery.domain.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTextTest {
    @Test
    fun cameraFileNamesBecomeWords() {
        assertEquals("img 20250814 101010", SearchText.filename("IMG_20250814_101010.jpg"))
        assertEquals("pxl 20250814 101010123 mp", SearchText.filename("PXL_20250814_101010123.MP.jpg"))
    }

    @Test
    fun camelCaseIsSplit() {
        assertEquals("my holiday photo", SearchText.filename("MyHolidayPhoto.png"))
    }

    @Test
    fun screenshotsAndSpacesAndPunctuation() {
        assertEquals("screenshot 2025 08 14 10 10 10", SearchText.filename("Screenshot_2025-08-14-10-10-10.png"))
        assertEquals("ricevuta ikea", SearchText.filename("Ricevuta IKEA.pdf"))
    }

    @Test
    fun accentsAreFolded() {
        assertEquals("citta di napoli", SearchText.filename("Città di Napoli.jpg"))
    }

    @Test
    fun aNameWithoutExtensionIsKept() {
        assertEquals("holiday", SearchText.filename("holiday"))
    }

    @Test
    fun normalizerFoldsCaseAccentsAndPunctuation() {
        assertEquals("citta e peru", TextNormalizer.name("  Città, e PERÙ!! "))
        assertEquals(listOf("emilia", "romagna"), TextNormalizer.words("Emilia-Romagna"))
    }
}
