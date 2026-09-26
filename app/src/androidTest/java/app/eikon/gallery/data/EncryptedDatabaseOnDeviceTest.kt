package app.eikon.gallery.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.eikon.gallery.data.backup.KeystoreSecretStore
import app.eikon.gallery.data.db.AlbumItemEntity
import app.eikon.gallery.data.db.DatabaseMigrations
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.db.MediaSearchEntity
import app.eikon.gallery.data.db.encryption.DatabaseKeys
import app.eikon.gallery.data.db.encryption.DatabaseNames
import app.eikon.gallery.data.db.encryption.EncryptedOpenHelperFactory
import app.eikon.gallery.data.db.encryption.KeyLookup
import app.eikon.gallery.data.db.encryption.Opened
import app.eikon.gallery.data.db.encryption.Protection
import app.eikon.gallery.data.db.encryption.SecureFiles
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library's encryption on the real thing: Room, SQLCipher and the Android Keystore. It works on a database, key file and Keystore key of its own names, so the app's own library
 * is never touched, and removes them afterwards.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseOnDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = DatabaseNames("eikon-instrumented-test")
    private val secrets = KeystoreSecretStore(context, "instrumented_test_db_secrets", "eikon-instrumented-test-db-key")
    private val keys = DatabaseKeys(secrets)
    private val reports = mutableListOf<Opened<*>>()

    private fun file(name: String) = context.getDatabasePath(name)

    @Before
    fun clean() = removeEverything()

    @After
    fun cleanUp() = removeEverything()

    private fun removeEverything() {
        listOf(names.plain, names.encrypted, names.staged).forEach { SecureFiles.wipe(SecureFiles.family(file(it))) }
        secrets.remove("database-key")
    }

    private fun encryptedRoom() = Room.databaseBuilder(context, EikonDatabase::class.java, names.plain)
        .openHelperFactory(EncryptedOpenHelperFactory(context, keys, names) { reports += it })
        .addMigrations(*DatabaseMigrations.ALL)
        .build()

    private fun readableRoom() = Room.databaseBuilder(context, EikonDatabase::class.java, names.plain)
        .addMigrations(*DatabaseMigrations.ALL)
        .build()

    private fun startsWithSqliteHeader(file: File) = file.inputStream().use { it.readNBytes(16) }.contentEquals("SQLite format 3\u0000".toByteArray())

    @Test
    fun aNewDatabaseIsEncryptedFromTheFirstByte() = runTest {
        val db = encryptedRoom()
        db.mediaDao().upsertAll(listOf(media(1)))
        db.close()

        assertTrue(file(names.encrypted).exists())
        assertFalse("nothing readable is left", file(names.plain).exists())
        assertFalse("the file must not look like a SQLite database", startsWithSqliteHeader(file(names.encrypted)))
        assertEquals(Protection.ENCRYPTED, reports.single().protection)
    }

    @Test
    fun theDatabaseIsThereAgainAfterARestart() = runTest {
        encryptedRoom().apply {
            mediaDao().upsertAll(listOf(media(1), media(2)))
            close()
        }

        val db = encryptedRoom()
        try {
            assertEquals(listOf(1L, 2L), db.mediaDao().allIds().sorted())
        } finally {
            db.close()
        }
    }

    @Test
    fun aLibraryFromBeforeTheEncryptionIsKeptWholeAndTheReadableFileGoes() = runTest {
        readableRoom().apply {
            mediaDao().upsertAll(listOf(media(7)))
            val album = albumDao().create("Trip", 0)
            albumDao().addItems(listOf(AlbumItemEntity(album, 7, 0)))
            indexDao().insertSearch(listOf(MediaSearchEntity(7, "beach.jpg", "café sunset")))
            metadataDao().setCaption(7, "Holiday in Napoli")
            close()
        }
        assertTrue(file(names.plain).exists())

        val db = encryptedRoom()
        try {
            assertEquals(listOf(7L), db.mediaDao().allIds())
            assertEquals(listOf("Trip"), db.albumDao().observeAlbums().first().map { it.name })
            assertEquals(listOf(7L), db.indexDao().existingSearchRows(listOf(7L)))
            assertEquals("Holiday in Napoli", db.metadataDao().caption(7))
        } finally {
            db.close()
        }
        assertFalse(file(names.plain).exists())
        assertFalse(startsWithSqliteHeader(file(names.encrypted)))
        assertEquals(Protection.ENCRYPTED, reports.single().protection)
    }

    @Test
    fun aWrongKeyCannotOpenIt() = runTest {
        encryptedRoom().apply {
            mediaDao().upsertAll(listOf(media(1)))
            close()
        }
        System.loadLibrary("sqlcipher")
        val path = file(names.encrypted).path

        try {
            SQLiteDatabase.openDatabase(path, "not the key".toByteArray(), null, SQLiteDatabase.OPEN_READONLY, null).use { it.rawQuery("SELECT count(*) FROM media", emptyArray<String>()).close() }
            fail("a wrong key must not open the database")
        } catch (_: android.database.sqlite.SQLiteException) {
            // expected
        }
    }

    @Test
    fun theRightKeyOpensItAndTheKeyIsNotStoredInTheClear() = runTest {
        encryptedRoom().apply {
            mediaDao().upsertAll(listOf(media(1)))
            close()
        }
        System.loadLibrary("sqlcipher")
        val key = (keys.lookup() as KeyLookup.Ready).key

        SQLiteDatabase.openDatabase(file(names.encrypted).path, key.passphrase(), null, SQLiteDatabase.OPEN_READONLY, null).use { db ->
            db.rawQuery("SELECT count(*) FROM media", emptyArray<String>()).use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
        }
        val stored = File(context.dataDir, "shared_prefs/instrumented_test_db_secrets.xml").readText()
        assertFalse("the key must be sealed on disk", key.stored() in stored)
    }

    @Test
    fun aStoredKeyThatIsLostStartsANewDatabaseAndSaysSo() = runTest {
        encryptedRoom().apply {
            mediaDao().upsertAll(listOf(media(1)))
            close()
        }
        val firstKey = (keys.lookup() as KeyLookup.Ready).key.stored()
        // What losing the Keystore key looks like: the stored value is there and cannot be opened.
        context.getSharedPreferences("instrumented_test_db_secrets", Context.MODE_PRIVATE).edit().putString("database-key", "AAAA").commit()
        reports.clear()

        val db = encryptedRoom()
        try {
            assertEquals(emptyList<Long>(), db.mediaDao().allIds())
        } finally {
            db.close()
        }
        assertEquals(Protection.RESET, reports.single().protection)
        assertNotEquals(firstKey, (keys.lookup() as KeyLookup.Ready).key.stored())
    }
}
