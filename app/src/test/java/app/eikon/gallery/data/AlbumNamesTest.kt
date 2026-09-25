package app.eikon.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumNamesTest {
    @Test
    fun namesAreTrimmed() {
        assertEquals("Summer 2025", AlbumNames.clean("  Summer 2025 \n"))
    }

    @Test
    fun blankNamesAreRejected() {
        assertNull(AlbumNames.clean(""))
        assertNull(AlbumNames.clean("   \t\n"))
    }

    @Test
    fun overLongNamesAreCutNotRejected() {
        val cleaned = AlbumNames.clean("a".repeat(AlbumNames.MAX_LENGTH + 25))
        assertEquals(AlbumNames.MAX_LENGTH, cleaned!!.length)
    }

    @Test
    fun cuttingNeverLeavesTrailingSpace() {
        val name = "a".repeat(AlbumNames.MAX_LENGTH - 1) + " b"
        assertEquals("a".repeat(AlbumNames.MAX_LENGTH - 1), AlbumNames.clean(name))
    }

    @Test
    fun unicodeAndPunctuationAreKept() {
        assertEquals("Città & Mare — 🌅", AlbumNames.clean("Città & Mare — 🌅"))
    }
}
