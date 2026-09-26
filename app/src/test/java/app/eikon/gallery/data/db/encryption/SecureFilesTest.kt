package app.eikon.gallery.data.db.encryption

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Deleting the readable database and the files SQLite keeps next to it. */
class SecureFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aDatabaseAndItsSideFilesAreOneFamily() {
        val db = File(folder.root, "eikon.db")
        assertEquals(
            listOf("eikon.db", "eikon.db-wal", "eikon.db-shm", "eikon.db-journal"),
            SecureFiles.family(db).map { it.name },
        )
    }

    @Test
    fun aFileIsOverwrittenWithZerosBeforeItGoes() {
        val file = folder.newFile("secret.db").apply { writeBytes(ByteArray(200_000) { 7 }) }
        // A second name for the same bytes lets the test see what was left in them once the first name is deleted.
        val link = File(folder.root, "link.db").also { Files.createLink(it.toPath(), file.toPath()) }

        assertTrue(SecureFiles.wipe(listOf(file)))

        assertFalse(file.exists())
        assertEquals(200_000L, link.length())
        assertTrue("the content was zeroed", link.readBytes().all { it == 0.toByte() })
    }

    @Test
    fun filesThatAreNotThereAreNotAnError() {
        assertTrue(SecureFiles.wipe(SecureFiles.family(File(folder.root, "absent.db"))))
    }

    @Test
    fun theWholeFamilyGoes() {
        val db = folder.newFile("eikon.db")
        val side = listOf("-wal", "-shm", "-journal").map { File(db.path + it).apply { writeText("x") } }

        assertTrue(SecureFiles.wipe(SecureFiles.family(db)))

        assertFalse(db.exists())
        side.forEach { assertFalse(it.name, it.exists()) }
    }

    @Test
    fun somethingThatCannotBeDeletedIsReportedNotThrown() {
        val directory = folder.newFolder("not-a-file")
        File(directory, "inside").writeText("x")

        assertFalse(SecureFiles.wipe(listOf(directory)))
    }
}
