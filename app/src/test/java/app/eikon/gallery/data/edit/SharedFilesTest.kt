package app.eikon.gallery.data.edit

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The temporary pictures made to share an edit, on a real folder. */
class SharedFilesTest {
    private lateinit var root: File

    @Before
    fun makeRoot() {
        root = Files.createTempDirectory("shared-files-test").toFile()
    }

    @After
    fun removeRoot() {
        root.deleteRecursively()
    }

    @Test
    fun everyShareGetsAFolderOfItsOwnEvenAtTheSameInstant() {
        val first = SharedFiles.newBatch(root, 1000)
        val second = SharedFiles.newBatch(root, 1000)

        assertNotEquals(first, second)
        assertTrue(first.isDirectory && second.isDirectory)
        assertEquals(root, first.parentFile)
    }

    @Test
    fun theNameIsTheOriginalsWithoutItsExtensionPlusEditAndJpg() {
        assertEquals("IMG_2025_edit.jpg", SharedFiles.fileName("IMG_2025.heic"))
        assertEquals("holiday.2025_edit.jpg", SharedFiles.fileName("holiday.2025.png"))
        assertEquals("noextension_edit.jpg", SharedFiles.fileName("noextension"))
    }

    @Test
    fun aNameWithNothingUsableStillGivesAFileName() {
        assertEquals("photo_edit.jpg", SharedFiles.fileName(".jpg"))
        assertEquals("photo_edit.jpg", SharedFiles.fileName(""))
    }

    @Test
    fun charactersThatCannotBeInAFileNameAreReplaced() {
        val name = SharedFiles.fileName("a/b\\c:d*e?f\"g<h>i|j.jpg")
        assertEquals("a_b_c_d_e_f_g_h_i_j_edit.jpg", name)
    }

    @Test
    fun onlyFoldersOlderThanTheLimitAreRemoved() {
        val old = SharedFiles.newBatch(root, 1).also { File(it, "a.jpg").writeText("x") }
        val fresh = SharedFiles.newBatch(root, 2)
        val now = 10 * SharedFiles.MAX_AGE_MS
        old.setLastModified(now - 2 * SharedFiles.MAX_AGE_MS)
        fresh.setLastModified(now - 1000)

        assertEquals(1, SharedFiles.sweep(root, now))

        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun sweepingAFolderThatDoesNotExistYetIsHarmless() {
        assertEquals(0, SharedFiles.sweep(File(root, "nothing-here"), 1000))
    }
}
