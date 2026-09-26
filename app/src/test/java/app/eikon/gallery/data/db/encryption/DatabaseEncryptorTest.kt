package app.eikon.gallery.data.db.encryption

import app.eikon.gallery.data.backup.SecretRead
import app.eikon.gallery.data.backup.SecretStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The rules for moving a library into the encrypted database, with real files and a stand-in for Room and SQLCipher (whose part is tested on a device): what is on disk after each
 * start-up, in every order things can go wrong, and above all that no readable copy is deleted before the encrypted one is complete.
 */
class DatabaseEncryptorTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class Secrets : SecretStore {
        val values = HashMap<String, String>()
        var unreadable = false
        var failure: RuntimeException? = null

        override fun get(name: String) = values[name]
        override fun put(name: String, value: String) {
            values[name] = value
        }

        override fun remove(name: String) {
            values.remove(name)
        }

        override fun read(name: String): SecretRead {
            failure?.let { throw it }
            return when {
                name !in values -> SecretRead.Missing
                unreadable -> SecretRead.Unreadable
                else -> SecretRead.Value(values.getValue(name))
            }
        }
    }

    /** Files stand for databases: the content of the encrypted one says what it was made from. */
    private inner class FakeEngine(private val secrets: Secrets) : DatabaseEngine<String> {
        val calls = mutableListOf<String>()
        var failAt: String? = null
        var leaveSideFile = false
        var keySeenByCreate: String? = null

        private fun file(name: String) = File(folder.root, name)

        private fun step(name: String) {
            calls += name
            if (failAt == name) throw IllegalStateException("$name failed")
        }

        override fun createEncrypted(name: String, key: DatabaseKey) {
            step("create")
            assertFalse("the staged file is cleared before it is made", file(name).exists())
            keySeenByCreate = secrets.values["database-key"]
            file(name).writeText("empty-encrypted")
            if (leaveSideFile) File(file(name).path + "-wal").writeText("half written")
        }

        override fun upgradePlain(name: String) = step("upgrade")

        override fun copy(plain: String, encrypted: String, key: DatabaseKey) {
            step("copy")
            file(encrypted).writeText("encrypted:" + file(plain).readText())
        }

        override fun verify(name: String, key: DatabaseKey) = step("verify")

        override fun useEncrypted(name: String, key: DatabaseKey) = "encrypted:$name"

        override fun usePlain(name: String) = "plain:$name"
    }

    private val secrets = Secrets()
    private val engine = FakeEngine(secrets)
    private lateinit var plain: File
    private lateinit var encrypted: File
    private lateinit var staged: File

    @Before
    fun paths() {
        plain = File(folder.root, "eikon.db")
        encrypted = File(folder.root, "eikon-encrypted.db")
        staged = File(folder.root, "eikon-encrypted.db.tmp")
    }

    private fun start() = DatabaseEncryptor(folder.root, DatabaseKeys(secrets), engine).open()

    private fun filesLeft() = folder.root.list()!!.sorted()

    @Test
    fun aFirstLaunchMakesAnEncryptedDatabaseAndNothingReadable() {
        val opened = start()

        assertEquals("encrypted:eikon-encrypted.db", opened.database)
        assertEquals(Protection.ENCRYPTED, opened.protection)
        assertEquals(listOf("eikon-encrypted.db"), filesLeft())
        assertEquals(listOf("create", "verify"), engine.calls)
    }

    @Test
    fun theKeyIsStoredBeforeAnythingIsEncryptedWithIt() {
        start()

        assertEquals(secrets.values["database-key"], engine.keySeenByCreate)
        assertNotEquals(null, engine.keySeenByCreate)
    }

    @Test
    fun aReadableDatabaseIsCopiedCheckedAndOnlyThenRemoved() {
        plain.writeText("my library")
        File(plain.path + "-wal").writeText("recent changes")
        File(plain.path + "-shm").writeText("index")

        val opened = start()

        assertEquals(Protection.ENCRYPTED, opened.protection)
        assertEquals("encrypted:eikon-encrypted.db", opened.database)
        assertEquals(listOf("upgrade", "create", "copy", "verify"), engine.calls)
        assertEquals(listOf("eikon-encrypted.db"), filesLeft())
        assertEquals("encrypted:my library", encrypted.readText())
    }

    @Test
    fun anEncryptedDatabaseIsJustOpenedTheNextTime() {
        start()
        engine.calls.clear()

        val opened = start()

        assertEquals("encrypted:eikon-encrypted.db", opened.database)
        assertEquals(Protection.ENCRYPTED, opened.protection)
        assertEquals("nothing is made or copied", emptyList<String>(), engine.calls)
    }

    @Test
    fun aReadableCopyLeftByAnInterruptedConversionIsRemovedWithoutTouchingTheEncryptedOne() {
        start()
        encrypted.writeText("complete encrypted database")
        plain.writeText("stale readable copy")
        engine.calls.clear()

        val opened = start()

        assertEquals(Protection.ENCRYPTED, opened.protection)
        assertEquals(listOf("eikon-encrypted.db"), filesLeft())
        assertEquals("complete encrypted database", encrypted.readText())
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun aFailedCopyLeavesTheReadableDatabaseAloneAndKeepsWorkingWithIt() {
        plain.writeText("my library")
        engine.failAt = "copy"

        val opened = start()

        assertEquals("plain:eikon.db", opened.database)
        assertEquals(Protection.NOT_ENCRYPTED, opened.protection)
        assertEquals("IllegalStateException", opened.failure)
        assertEquals("my library", plain.readText())
        assertEquals("the half-made encrypted file is gone", listOf("eikon.db"), filesLeft())
    }

    @Test
    fun theNextStartTriesAgainAndSucceeds() {
        plain.writeText("my library")
        engine.failAt = "copy"
        start()
        engine.failAt = null
        engine.calls.clear()

        val opened = start()

        assertEquals(Protection.ENCRYPTED, opened.protection)
        assertEquals(listOf("upgrade", "create", "copy", "verify"), engine.calls)
        assertEquals(listOf("eikon-encrypted.db"), filesLeft())
    }

    @Test
    fun anEncryptedFileThatFailsItsCheckIsNotKeptAndTheReadableOneSurvives() {
        plain.writeText("my library")
        engine.failAt = "verify"

        val opened = start()

        assertEquals(Protection.NOT_ENCRYPTED, opened.protection)
        assertEquals("my library", plain.readText())
        assertEquals("otherwise the next start would trust it and delete the readable one", listOf("eikon.db"), filesLeft())
    }

    @Test
    fun aFailureWhileUpgradingTheReadableDatabaseChangesNothing() {
        plain.writeText("my library")
        engine.failAt = "upgrade"

        val opened = start()

        assertEquals(Protection.NOT_ENCRYPTED, opened.protection)
        assertEquals(listOf("eikon.db"), filesLeft())
        assertEquals(listOf("upgrade"), engine.calls)
    }

    @Test
    fun ifAnEncryptedDatabaseCannotBeMadeTheAppStillStartsAndSaysSo() {
        engine.failAt = "create"

        val opened = start()

        assertEquals("plain:eikon.db", opened.database)
        assertEquals(Protection.NOT_ENCRYPTED, opened.protection)
        assertEquals(emptyList<String>(), filesLeft())
    }

    @Test
    fun aStagedDatabaseThatWasNotClosedCleanlyIsNeverPromoted() {
        plain.writeText("my library")
        engine.leaveSideFile = true

        val opened = start()

        assertEquals(Protection.NOT_ENCRYPTED, opened.protection)
        assertEquals(listOf("eikon.db"), filesLeft())
    }

    @Test
    fun anEmptyJournalIsNotAProblem() {
        plain.writeText("my library")
        // SQLite's TRUNCATE mode leaves its journal behind, empty.
        File(staged.path + "-journal").writeText("")
        val opened = DatabaseEncryptor(folder.root, DatabaseKeys(secrets), object : DatabaseEngine<String> by engine {
            override fun createEncrypted(name: String, key: DatabaseKey) {
                engine.createEncrypted(name, key)
                File(folder.root, "$name-journal").writeText("")
            }
        }).open()

        assertEquals(Protection.ENCRYPTED, opened.protection)
        assertEquals(listOf("eikon-encrypted.db"), filesLeft())
    }

    @Test
    fun leftoversOfAnEarlierAttemptAreClearedFirst() {
        plain.writeText("my library")
        staged.writeText("half a database from a crash")
        File(staged.path + "-journal").writeText("stale")

        val opened = start()

        assertEquals(Protection.ENCRYPTED, opened.protection)
        assertEquals("encrypted:my library", encrypted.readText())
        assertEquals(listOf("eikon-encrypted.db"), filesLeft())
    }

    @Test
    fun aLostKeyStartsOverAndSaysSo() {
        start()
        val firstKey = secrets.values.getValue("database-key")
        encrypted.writeText("data nobody can read now")
        secrets.unreadable = true
        engine.calls.clear()

        val opened = start()

        assertEquals(Protection.RESET, opened.protection)
        assertEquals(listOf("create", "verify"), engine.calls)
        assertEquals("empty-encrypted", encrypted.readText())
        assertNotEquals(firstKey, secrets.values.getValue("database-key"))
        assertEquals(listOf("eikon-encrypted.db"), filesLeft())
    }

    @Test
    fun aLostKeyWithAReadableCopyStillLeftUsesTheCopy() {
        start()
        encrypted.writeText("data nobody can read now")
        plain.writeText("older readable library")
        secrets.unreadable = true

        val opened = start()

        assertEquals(Protection.RESET, opened.protection)
        assertEquals("encrypted:older readable library", encrypted.readText())
        assertEquals(listOf("eikon-encrypted.db"), filesLeft())
    }

    @Test
    fun aFailureOfTheKeyStoreDeletesNothing() {
        start()
        encrypted.writeText("precious")
        secrets.failure = IllegalStateException("the Keystore is busy")

        try {
            start()
            fail("expected the failure to reach the caller")
        } catch (e: IllegalStateException) {
            assertEquals("the Keystore is busy", e.message)
        }

        assertEquals("precious", encrypted.readText())
    }

    @Test
    fun theSuccessOfAConversionCarriesNoFailure() {
        plain.writeText("my library")

        assertNull(start().failure)
        assertTrue(encrypted.exists())
    }
}
