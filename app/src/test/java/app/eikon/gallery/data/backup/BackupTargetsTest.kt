package app.eikon.gallery.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.eikon.gallery.data.db.BackupDao
import app.eikon.gallery.data.db.BackupItemEntity
import app.eikon.gallery.data.db.MediaEntity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** What is missing from the settings is said before anything is sent anywhere. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupTargetsTest {
    private lateinit var directory: File
    private val secrets = object : SecretStore {
        val values = HashMap<String, String>()
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String) {
            values[name] = value
        }

        override fun remove(name: String) {
            values.remove(name)
        }
    }

    @Before
    fun makeDirectory() {
        directory = Files.createTempDirectory("backup-targets-test").toFile()
    }

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    /** Settings where the user has allowed the network (what every test but the consent ones starts from). */
    private suspend fun TestScope.repository(): BackupSettingsRepository = unconsented().also { it.update { s -> s.copy(networkAllowed = true) } }

    private fun TestScope.unconsented() = BackupSettingsRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "s.preferences_pb") },
        secrets,
        object : BackupDao {
            override suspend fun pending(includeVideos: Boolean, includeHidden: Boolean, maxAttempts: Int, limit: Int): List<MediaEntity> = emptyList()
            override fun observeTotal(includeVideos: Boolean, includeHidden: Boolean): Flow<Int> = flowOf(0)
            override fun observeDone(includeVideos: Boolean, includeHidden: Boolean): Flow<Int> = flowOf(0)
            override fun observeFailed(includeVideos: Boolean, includeHidden: Boolean): Flow<Int> = flowOf(0)
            override suspend fun get(mediaId: Long): BackupItemEntity? = null
            override suspend fun put(item: BackupItemEntity) = Unit
            override suspend fun forgetFailures() = Unit
            override suspend fun clear() = Unit
        },
    )

    @Test
    fun withoutTheUsersConsentToTheNetworkNoConnectionIsEvenPrepared() = runTest(UnconfinedTestDispatcher()) {
        val repository = unconsented()
        // Everything else is in order: a server, a key. Only the consent is missing.
        repository.update { it.copy(kind = ServerKind.IMMICH, serverUrl = "https://photos.example.com", enabled = true) }
        repository.setCredential("key")
        try {
            BackupTargets(repository).create()
            fail("built a target although the network is not allowed")
        } catch (_: BackupException.NetworkOff) {
        }
    }

    @Test
    fun takingTheConsentBackStopsIt() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.update { it.copy(kind = ServerKind.IMMICH, serverUrl = "https://photos.example.com") }
        repository.setCredential("key")
        BackupTargets(repository).create()
        repository.update { it.copy(networkAllowed = false) }
        try {
            BackupTargets(repository).create()
            fail("built a target after the consent was taken back")
        } catch (_: BackupException.NetworkOff) {
        }
    }

    @Test
    fun noAddressIsSaidBeforeAnythingElse() = runTest(UnconfinedTestDispatcher()) {
        try {
            BackupTargets(repository()).create()
            fail()
        } catch (e: BackupException.Misconfigured) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun anUnencryptedAddressIsNeverAccepted() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.update { it.copy(kind = ServerKind.IMMICH, serverUrl = "http://192.168.1.5:2283") }
        repository.setCredential("key")
        try {
            BackupTargets(repository).create()
            fail("accepted an http address")
        } catch (_: BackupException.Misconfigured) {
        }
    }

    @Test
    fun aMissingCredentialIsSaid() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.update { it.copy(kind = ServerKind.IMMICH, serverUrl = "https://photos.example.com") }
        try {
            BackupTargets(repository).create()
            fail()
        } catch (_: BackupException.Unauthorized) {
        }
    }

    @Test
    fun nextcloudNeedsALoginName() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.update { it.copy(kind = ServerKind.NEXTCLOUD, serverUrl = "https://cloud.example.com") }
        repository.setCredential("pw")
        try {
            BackupTargets(repository).create()
            fail()
        } catch (e: BackupException.Misconfigured) {
            assertTrue(e.message!!.contains("login"))
        }
    }

    @Test
    fun eachKindGetsItsOwnTarget() = runTest(UnconfinedTestDispatcher()) {
        val repository = repository()
        repository.update { it.copy(kind = ServerKind.IMMICH, serverUrl = "https://photos.example.com") }
        repository.setCredential("key")
        assertTrue(BackupTargets(repository).create() is ImmichTarget)

        repository.update { it.copy(kind = ServerKind.NEXTCLOUD, serverUrl = "https://cloud.example.com", username = "me") }
        repository.setCredential("pw")
        assertTrue(BackupTargets(repository).create() is NextcloudTarget)
    }
}
