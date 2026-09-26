package app.eikon.gallery.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.eikon.gallery.core.di.BackupStore
import app.eikon.gallery.data.db.BackupDao
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** How the last run ended, for the settings screen. */
data class BackupRunSummary(val at: Long, val sent: Int, val alreadyThere: Int, val refused: Int, val stoppedBy: BackupStop?, val message: String?)

/**
 * The backup's settings, the summary of its last run and its credential. A credential belongs to one server: changing the address, the login or the kind of server throws it away
 * together with the trust given to a certificate and the list of what was sent (all of which are about the old server), and turns the backup off, so nothing is ever sent to a
 * new place with what was set up for an old one.
 */
@Singleton
class BackupSettingsRepository @Inject constructor(
    @BackupStore private val store: DataStore<Preferences>,
    private val secrets: SecretStore,
    private val dao: BackupDao,
) {
    // Opening a sealed value asks the Keystore, which is slow enough to keep off the main thread.
    val settings: Flow<BackupSettings> = store.data.map(::toSettings).distinctUntilChanged().flowOn(Dispatchers.Default)

    val lastRun: Flow<BackupRunSummary?> = store.data.map(::toSummary).distinctUntilChanged().flowOn(Dispatchers.Default)

    suspend fun current(): BackupSettings = settings.first()

    suspend fun update(change: (BackupSettings) -> BackupSettings) {
        val before = current()
        var after = change(before)
        val moved = before.destination != after.destination
        if (moved) after = after.copy(enabled = false, pinnedCertificate = null)
        store.edit { write(it, after) }
        if (moved) {
            withContext(Dispatchers.IO) { secrets.remove(CREDENTIAL) }
            dao.clear()
        }
    }

    /** Whether a credential is stored (it is not opened: this is cheap enough to ask from a screen). */
    fun hasCredential(): Boolean = secrets.has(CREDENTIAL)

    // Opening and sealing go through the Android Keystore and a file: not for the main thread.
    suspend fun credential(): String? = withContext(Dispatchers.IO) { secrets.get(CREDENTIAL) }

    suspend fun setCredential(value: String) = withContext(Dispatchers.IO) {
        if (value.isBlank()) secrets.remove(CREDENTIAL) else secrets.put(CREDENTIAL, value.trim())
    }

    /** A random name for this installation, made once; it tells a server which of its uploaders a photo came from and identifies nothing else. */
    suspend fun installId(): String {
        store.data.first()[INSTALL_ID]?.let { return it }
        val made = "eikon-" + UUID.randomUUID().toString().take(ID_CHARS)
        store.edit { if (it[INSTALL_ID] == null) it[INSTALL_ID] = made }
        return store.data.first()[INSTALL_ID] ?: made
    }

    suspend fun recordRun(report: BackupReport, at: Long) {
        store.edit {
            it[LAST_AT] = at
            it[LAST_SENT] = report.sent
            it[LAST_THERE] = report.alreadyThere
            it[LAST_REFUSED] = report.refused
            if (report.stoppedBy == null) it.remove(LAST_STOP) else it[LAST_STOP] = report.stoppedBy.name
            if (report.message == null) it.remove(LAST_MESSAGE) else it[LAST_MESSAGE] = sealed("last_message", report.message)
        }
    }

    private fun write(prefs: androidx.datastore.preferences.core.MutablePreferences, s: BackupSettings) {
        prefs[ENABLED] = s.enabled
        prefs[KIND] = s.kind.name
        prefs[URL] = sealed("url", s.serverUrl)
        prefs[USER] = sealed("user", s.username)
        prefs[FOLDER] = s.folder
        prefs[WIFI_ONLY] = s.wifiOnly
        prefs[CHARGING_ONLY] = s.chargingOnly
        prefs[VIDEOS] = s.includeVideos
        prefs[HIDDEN] = s.includeHidden
        if (s.pinnedCertificate == null) prefs.remove(PIN) else prefs[PIN] = sealed("pin", s.pinnedCertificate)
    }

    /** The address, the login, the pinned certificate and the last message say where the user's server is and who they are on it, so they are kept sealed under the Keystore, not in the clear. */
    private fun sealed(name: String, value: String) = SEALED + secrets.seal(name, value)

    /** A value written before sealing existed is accepted as it is (and sealed the next time settings are saved); a sealed one that cannot be opened (its key is gone) reads as not set. */
    private fun opened(name: String, stored: String?): String? = when {
        stored == null -> null
        !stored.startsWith(SEALED) -> stored
        else -> secrets.unseal(name, stored.removePrefix(SEALED))
    }

    private fun toSettings(p: Preferences) = BackupSettings(
        enabled = p[ENABLED] ?: false,
        kind = ServerKind.entries.firstOrNull { it.name == p[KIND] } ?: ServerKind.NEXTCLOUD,
        serverUrl = opened("url", p[URL]).orEmpty(),
        username = opened("user", p[USER]).orEmpty(),
        folder = p[FOLDER] ?: BackupSettings.DEFAULT_FOLDER,
        wifiOnly = p[WIFI_ONLY] ?: true,
        chargingOnly = p[CHARGING_ONLY] ?: false,
        includeVideos = p[VIDEOS] ?: true,
        includeHidden = p[HIDDEN] ?: false,
        pinnedCertificate = opened("pin", p[PIN]),
    )

    private fun toSummary(p: Preferences): BackupRunSummary? {
        val at = p[LAST_AT] ?: return null
        return BackupRunSummary(
            at, p[LAST_SENT] ?: 0, p[LAST_THERE] ?: 0, p[LAST_REFUSED] ?: 0,
            BackupStop.entries.firstOrNull { it.name == p[LAST_STOP] }, opened("last_message", p[LAST_MESSAGE]),
        )
    }

    companion object {
        const val CREDENTIAL = "credential"
        private const val SEALED = "sealed:"
        private const val ID_CHARS = 8
        private val ENABLED = booleanPreferencesKey("enabled")
        private val KIND = stringPreferencesKey("kind")
        private val URL = stringPreferencesKey("url")
        private val USER = stringPreferencesKey("user")
        private val FOLDER = stringPreferencesKey("folder")
        private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        private val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        private val VIDEOS = booleanPreferencesKey("videos")
        private val HIDDEN = booleanPreferencesKey("hidden")
        private val PIN = stringPreferencesKey("pinned_certificate")
        private val INSTALL_ID = stringPreferencesKey("install_id")
        private val LAST_AT = longPreferencesKey("last_at")
        private val LAST_SENT = intPreferencesKey("last_sent")
        private val LAST_THERE = intPreferencesKey("last_there")
        private val LAST_REFUSED = intPreferencesKey("last_refused")
        private val LAST_STOP = stringPreferencesKey("last_stop")
        private val LAST_MESSAGE = stringPreferencesKey("last_message")
    }
}
