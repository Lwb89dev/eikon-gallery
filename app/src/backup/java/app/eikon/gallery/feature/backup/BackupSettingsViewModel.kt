package app.eikon.gallery.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.data.backup.BackupProgress
import app.eikon.gallery.data.backup.BackupRunSummary
import app.eikon.gallery.data.backup.BackupException
import app.eikon.gallery.data.backup.BackupSettings
import app.eikon.gallery.data.backup.BackupSettingsRepository
import app.eikon.gallery.data.backup.BackupTargets
import app.eikon.gallery.data.backup.HomeServerBackup
import app.eikon.gallery.data.backup.PresentedCertificate
import app.eikon.gallery.data.backup.ServerCheck
import app.eikon.gallery.data.backup.ServerKind
import app.eikon.gallery.data.backup.Tls
import app.eikon.gallery.data.db.BackupCounts
import app.eikon.gallery.data.db.BackupDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the form holds while it is being filled in. Nothing here is saved (or can wipe what was saved) until "Save and test". */
data class BackupForm(
    val kind: ServerKind = ServerKind.NEXTCLOUD,
    val url: String = "",
    val username: String = "",
    val folder: String = BackupSettings.DEFAULT_FOLDER,
    /** What was typed for the API key or password; empty means "keep what is saved". */
    val credential: String = "",
)

/** Where the connection test stands. */
sealed interface ConnectionState {
    data object Untested : ConnectionState
    data object Testing : ConnectionState
    data object Declined : ConnectionState
    data class Connected(val check: ServerCheck) : ConnectionState
    data class Failed(val message: String) : ConnectionState

    /** The server's certificate is not one the phone trusts; the user is shown it and decides. */
    data class NeedsTrust(val certificate: PresentedCertificate, val host: String) : ConnectionState
}

@HiltViewModel
class BackupSettingsViewModel @Inject constructor(
    private val repository: BackupSettingsRepository,
    private val targets: BackupTargets,
    private val service: HomeServerBackup,
    private val access: MediaAccessChecker,
    private val dao: BackupDao,
    progress: BackupProgress,
) : ViewModel() {
    val settings: StateFlow<BackupSettings> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BackupSettings())
    val counts: StateFlow<BackupCounts> = progress.counts.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BackupCounts(0, 0, 0))
    val lastRun: StateFlow<BackupRunSummary?> = repository.lastRun.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)
    val running: StateFlow<Boolean> = service.isRunning.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private val formState = MutableStateFlow(BackupForm())
    val form: StateFlow<BackupForm> = formState.asStateFlow()

    private val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Untested)
    val connection: StateFlow<ConnectionState> = connectionState.asStateFlow()

    private val credentialSaved = MutableStateFlow(repository.hasCredential())
    val hasCredential: StateFlow<Boolean> = credentialSaved.asStateFlow()

    private val locationAllowed = MutableStateFlow(access.canReadLocation())
    val canReadLocation: StateFlow<Boolean> = locationAllowed.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = repository.current()
            formState.update { BackupForm(saved.kind, saved.serverUrl, saved.username, saved.folder) }
        }
    }

    fun refreshPermission() {
        locationAllowed.value = access.canReadLocation()
        credentialSaved.value = repository.hasCredential()
    }

    fun edit(change: (BackupForm) -> BackupForm) = formState.update(change)

    /** Saves the form (a different server starts from nothing: see [BackupSettingsRepository.update]) and tests the connection. */
    fun saveAndTest() {
        viewModelScope.launch {
            connectionState.value = ConnectionState.Testing
            val f = formState.value
            repository.update { it.copy(kind = f.kind, serverUrl = f.url.trim(), username = f.username.trim(), folder = f.folder.trim().ifEmpty { BackupSettings.DEFAULT_FOLDER }) }
            if (f.credential.isNotBlank()) repository.setCredential(f.credential)
            formState.update { it.copy(credential = "") }
            credentialSaved.value = repository.hasCredential()
            test()
        }
    }

    /** The user compared the fingerprint and agrees: from now on this one certificate is accepted for this server. */
    fun trustCertificate() {
        val pending = connectionState.value as? ConnectionState.NeedsTrust ?: return
        viewModelScope.launch {
            repository.update { it.copy(pinnedCertificate = pending.certificate.fingerprint) }
            connectionState.value = ConnectionState.Testing
            test()
        }
    }

    fun refuseCertificate() {
        connectionState.value = ConnectionState.Declined
    }

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun setWifiOnly(value: Boolean) = update { it.copy(wifiOnly = value) }

    fun setChargingOnly(value: Boolean) = update { it.copy(chargingOnly = value) }

    fun setIncludeVideos(value: Boolean) = update { it.copy(includeVideos = value) }

    fun setIncludeHidden(value: Boolean) = update { it.copy(includeHidden = value) }

    fun backUpNow() = service.backUpNow()

    /** Gives the photos the server refused another go. */
    fun retryRefused() {
        viewModelScope.launch { dao.forgetFailures() }
    }

    private fun update(change: (BackupSettings) -> BackupSettings) {
        viewModelScope.launch { repository.update(change) }
    }

    private suspend fun test() {
        connectionState.value = try {
            ConnectionState.Connected(targets.create().check())
        } catch (untrusted: BackupException.Untrusted) {
            probe(untrusted)
        } catch (e: BackupException) {
            ConnectionState.Failed(e.message ?: "The connection failed.")
        }
    }

    /** The phone did not accept the certificate: read it (without trusting it) so the user can see what it is. */
    private suspend fun probe(untrusted: BackupException.Untrusted): ConnectionState {
        val url = BackupSettings.normalizedUrl(repository.current().serverUrl).toHttpUrlOrNull() ?: return ConnectionState.Failed(untrusted.message.orEmpty())
        return try {
            ConnectionState.NeedsTrust(withContext(Dispatchers.IO) { Tls.probe(url.host, url.port) }, url.host)
        } catch (e: java.io.IOException) {
            ConnectionState.Failed("${untrusted.message} (Its certificate could not be read: ${e.message})")
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
