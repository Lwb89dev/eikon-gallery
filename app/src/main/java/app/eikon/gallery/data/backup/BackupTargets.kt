package app.eikon.gallery.data.backup

import javax.inject.Inject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Builds the target the settings describe, or says what is missing. */
class BackupTargets @Inject constructor(private val settings: BackupSettingsRepository) {
    suspend fun create(): BackupTarget {
        val current = settings.current()
        // The choke point of everything that connects: without the user's consent to the network not even a client is built.
        if (!current.networkAllowed) throw BackupException.NetworkOff()
        val address = BackupSettings.normalizedUrl(current.serverUrl).toHttpUrlOrNull()
            ?: throw BackupException.Misconfigured("The address of the server is missing or is not an https address.")
        val credential = settings.credential() ?: throw BackupException.Unauthorized("No API key or password is set.")
        val client = Tls.client(current.pinnedCertificate)
        return when (current.kind) {
            ServerKind.IMMICH -> ImmichTarget(client, address, credential, settings.installId())
            ServerKind.NEXTCLOUD -> {
                if (current.username.isBlank()) throw BackupException.Misconfigured("The Nextcloud login name is missing.")
                NextcloudTarget(client, address, current.username.trim(), credential, current.folder)
            }
        }
    }
}
