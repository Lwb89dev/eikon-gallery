package app.eikon.gallery.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteNamesTest {
    private fun file(name: String = "IMG_1234.JPG", takenAt: Long = 1_755_000_000_000L, sha1: String = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678") =
        BackupFile(1, name, "image/jpeg", false, takenAt, 1, false, 100, sha1)

    @Test
    fun aFileGoesInTheFolderOfTheDayItWasTakenWithItsHashInTheName() {
        // 2025-08-12 UTC
        assertEquals("eikon/2025/08/IMG_1234_a1b2c3d4.jpg", RemoteNames.path("eikon", file()))
    }

    @Test
    fun theSamePictureAlwaysGetsTheSamePlace() {
        assertEquals(RemoteNames.path("eikon", file()), RemoteNames.path("eikon", file()))
    }

    @Test
    fun twoDifferentPicturesWithTheSameNameNeverShareAPlace() {
        val a = RemoteNames.path("eikon", file(sha1 = "aaaaaaaa11111111"))
        val b = RemoteNames.path("eikon", file(sha1 = "bbbbbbbb22222222"))
        assertNotEquals(a, b)
    }

    @Test
    fun theDayIsInUtcSoTheTimeZoneOfThePhoneDoesNotMatter() {
        // 2025-12-31 23:30 UTC and 2026-01-01 00:30 UTC are different folders in every zone.
        assertTrue(RemoteNames.path("eikon", file(takenAt = 1_767_223_800_000L)).contains("/2025/12/"))
        assertTrue(RemoteNames.path("eikon", file(takenAt = 1_767_227_400_000L)).contains("/2026/01/"))
    }

    @Test
    fun aFolderCanBeSeveralLevelsButNeverEscape() {
        assertEquals(listOf("Photos", "phone"), RemoteNames.segments("/Photos//phone/"))
        assertEquals(listOf("a", "b"), RemoteNames.segments("a/../b/./"))
        assertEquals(listOf("etc"), RemoteNames.segments("..\\etc"))
        assertEquals(emptyList<String>(), RemoteNames.segments("  "))
    }

    @Test
    fun anEmptyFolderPutsFilesDirectlyInTheYearFolder() {
        assertEquals("2025/08/IMG_1234_a1b2c3d4.jpg", RemoteNames.path("", file()))
    }

    @Test
    fun charactersAServerMayRefuseAreReplaced() {
        val path = RemoteNames.path("eikon", file(name = "a:b*c?d\"e<f>g|h.png"))
        assertEquals("eikon/2025/08/a_b_c_d_e_f_g_h_a1b2c3d4.png", path)
    }

    @Test
    fun aNameWithoutAnExtensionOrWithNothingUsableStillWorks() {
        assertEquals("eikon/2025/08/holiday_a1b2c3d4", RemoteNames.path("eikon", file(name = "holiday")))
        assertEquals("eikon/2025/08/photo_a1b2c3d4", RemoteNames.path("eikon", file(name = "")))
    }

    @Test
    fun aVeryLongNameIsCutSoThePathStaysUsable() {
        val long = "x".repeat(400) + ".jpg"
        val name = RemoteNames.path("eikon", file(name = long)).substringAfterLast('/')
        assertTrue(name.length < 140)
        assertTrue(name.endsWith("_a1b2c3d4.jpg"))
    }
}
