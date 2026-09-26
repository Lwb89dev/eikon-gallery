package app.eikon.gallery.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import app.eikon.gallery.data.db.BackupDao
import app.eikon.gallery.data.db.BackupItemEntity
import app.eikon.gallery.data.db.MediaEntity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The backup's settings on a real DataStore: what a change of server throws away, what it keeps, and that the credential is never kept in the settings. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupSettingsRepositoryTest {
    private lateinit var directory: File
    private val secrets = FakeSecrets()
    private val dao = FakeBackupDao()

    @Before
    fun makeDirectory() {
        directory = Files.createTempDirectory("backup-settings-test").toFile()
    }

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    private fun TestScope.repository(name: String = "settings") =
        BackupSettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "$name.preferences_pb") }, secrets, dao)

    private val nextcloud = BackupSettings(kind = ServerKind.NEXTCLOUD, serverUrl = "https://cloud.example.com", username = "me")

    @Test
    fun nothingIsOnBeforeTheUserSetsItUp() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()

        assertEquals(BackupSettings(), repository.current())
        assertFalse(repository.hasCredential())
        assertNull(repository.lastRun.first())
    }

    @Test
    fun settingsComeBackAsTheyWereSaved() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        val saved = nextcloud.copy(enabled = false, folder = "Photos/phone", wifiOnly = false, chargingOnly = true, includeVideos = false, includeHidden = true, pinnedCertificate = "ab".repeat(32))

        repository.update { saved }

        // Saving a server for the first time is a change of destination: it comes back turned off and without a pin, whatever was asked.
        assertEquals(saved.copy(pinnedCertificate = null), repository.current())
    }

    @Test
    fun optionsThatDoNotChangeTheDestinationArePlainlyKept() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.update { nextcloud }
        repository.setCredential("app-password")
        dao.cleared = false

        repository.update { it.copy(enabled = true, wifiOnly = false, includeHidden = true, pinnedCertificate = "cd".repeat(32)) }

        val now = repository.current()
        assertTrue(now.enabled)
        assertFalse(now.wifiOnly)
        assertEquals("cd".repeat(32), now.pinnedCertificate)
        assertEquals("app-password", repository.credential())
        assertFalse(dao.cleared)
    }

    @Test
    fun aDifferentServerThrowsAwayEverythingAboutTheOldOneAndTurnsTheBackupOff() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.update { nextcloud }
        repository.setCredential("app-password")
        repository.update { it.copy(enabled = true, pinnedCertificate = "cd".repeat(32)) }
        dao.cleared = false

        repository.update { it.copy(serverUrl = "https://other.example.com") }

        val now = repository.current()
        assertFalse(now.enabled)
        assertNull(now.pinnedCertificate)
        assertNull(repository.credential())
        assertTrue(dao.cleared)
    }

    @Test
    fun aNewLoginOrFolderOrKindCountsAsANewDestinationToo() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.update { nextcloud }
        for (change in listOf<(BackupSettings) -> BackupSettings>({ it.copy(username = "you") }, { it.copy(folder = "elsewhere") }, { it.copy(kind = ServerKind.IMMICH) })) {
            repository.setCredential("pw")
            dao.cleared = false
            repository.update(change)
            assertNull(repository.credential())
            assertTrue(dao.cleared)
            repository.update { nextcloud }
        }
    }

    @Test
    fun theSameAddressWrittenDifferentlyIsNotADifferentServer() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.update { nextcloud }
        repository.setCredential("pw")
        dao.cleared = false

        repository.update { it.copy(serverUrl = " HTTPS://Cloud.Example.com/ ") }

        assertEquals("pw", repository.credential())
        assertFalse(dao.cleared)
    }

    @Test
    fun aBlankCredentialRemovesItAndTheCredentialIsNotInTheSettingsFile() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.setCredential("  very-secret-value  ")
        assertEquals("very-secret-value", repository.credential())
        repository.update { nextcloud }
        directory.walkTopDown().filter { it.isFile }.forEach { assertFalse(it.readText(Charsets.ISO_8859_1).contains("very-secret-value")) }

        repository.setCredential("   ")
        assertFalse(repository.hasCredential())
    }

    @Test
    fun theAddressTheLoginTheCertificateAndTheLastMessageAreNotInTheSettingsFileInTheClear() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        val saved = nextcloud.copy(serverUrl = "https://home.example.org", username = "alice-nextcloud")
        repository.update { saved }
        repository.update { it.copy(pinnedCertificate = "ef".repeat(32)) }
        repository.recordRun(BackupReport(1, 0, 0, BackupStop.UNREACHABLE, "cannot reach nas.home.example.org"), at = 5L)

        val file = directory.walkTopDown().filter { it.isFile }.joinToString("") { it.readText(Charsets.ISO_8859_1) }
        listOf("home.example.org", "alice-nextcloud", "ef".repeat(32), "nas.home").forEach { assertFalse(it, file.contains(it)) }
        assertEquals(saved.serverUrl, repository.current().serverUrl)
        assertEquals("alice-nextcloud", repository.current().username)
        assertEquals("ef".repeat(32), repository.current().pinnedCertificate)
        assertEquals("cannot reach nas.home.example.org", repository.lastRun.first()!!.message)
    }

    @Test
    fun sealedValuesThatCannotBeOpenedReadAsNotSetInsteadOfFailing() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.update { nextcloud }
        secrets.keyLost = true

        val now = repository.current()

        assertEquals("", now.serverUrl)
        assertEquals("", now.username)
        assertFalse(now.enabled)
    }

    @Test
    fun aSettingsFileWrittenBeforeSealingExistedIsStillRead() = runTest(UnconfinedTestDispatcher()) {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "legacy.preferences_pb") }
        store.edit {
            it[androidx.datastore.preferences.core.stringPreferencesKey("url")] = "https://legacy.example.com"
            it[androidx.datastore.preferences.core.stringPreferencesKey("user")] = "old-login"
        }
        val repository = BackupSettingsRepository(store, secrets, dao)

        assertEquals("https://legacy.example.com", repository.current().serverUrl)
        assertEquals("old-login", repository.current().username)
        // Saving anything seals what was there.
        repository.update { it.copy(wifiOnly = false) }
        assertFalse(directory.walkTopDown().filter { it.isFile }.any { it.readText(Charsets.ISO_8859_1).contains("legacy.example.com") })
        assertEquals("https://legacy.example.com", repository.current().serverUrl)
    }

    @Test
    fun theInstallNameIsMadeOnceAndKept() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        val first = repository.installId()
        assertTrue(first.startsWith("eikon-"))
        assertEquals(first, repository.installId())
    }

    @Test
    fun theLastRunIsRememberedWithHowItEnded() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()

        repository.recordRun(BackupReport(sent = 5, alreadyThere = 2, refused = 1, stoppedBy = BackupStop.UNREACHABLE, message = "no route"), at = 1234L)
        assertEquals(BackupRunSummary(1234L, 5, 2, 1, BackupStop.UNREACHABLE, "no route"), repository.lastRun.first())

        repository.recordRun(BackupReport(1, 0, 0, null), at = 2000L)
        assertEquals(BackupRunSummary(2000L, 1, 0, 0, null, null), repository.lastRun.first())
    }

    private class FakeSecrets : SecretStore {
        private val values = HashMap<String, String>()
        private val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val box = SecretBox { key }

        /** What losing the Keystore key looks like: nothing sealed can be opened any more. */
        var keyLost = false

        override fun seal(name: String, value: String) = box.seal(name, value)
        override fun unseal(name: String, sealed: String): String? = if (keyLost) null else box.open(name, sealed)
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String) {
            values[name] = value
        }

        override fun remove(name: String) {
            values.remove(name)
        }
    }

    private class FakeBackupDao : BackupDao {
        var cleared = false
        override suspend fun pending(includeVideos: Boolean, includeHidden: Boolean, maxAttempts: Int, limit: Int): List<MediaEntity> = emptyList()
        override fun observeTotal(includeVideos: Boolean, includeHidden: Boolean): Flow<Int> = flowOf(0)
        override fun observeDone(includeVideos: Boolean, includeHidden: Boolean): Flow<Int> = flowOf(0)
        override fun observeFailed(includeVideos: Boolean, includeHidden: Boolean): Flow<Int> = flowOf(0)
        override suspend fun get(mediaId: Long): BackupItemEntity? = null
        override suspend fun put(item: BackupItemEntity) = Unit
        override suspend fun forgetFailures() = Unit
        override suspend fun clear() {
            cleared = true
        }
    }
}
