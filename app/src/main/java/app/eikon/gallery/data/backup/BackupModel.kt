package app.eikon.gallery.data.backup

import java.io.InputStream

/** The kinds of home server eikon can copy photos to. Both are software you run yourself; nothing here is a service of ours or of anyone else's. */
enum class ServerKind {
    /** Immich: a self-hosted photo library (it is what Umbrel installs for Android). eikon uploads with an API key and lets Immich recognise copies it already has. */
    IMMICH,

    /** Nextcloud, or any server that speaks WebDAV: eikon writes plain files into a folder, with an app password. */
    NEXTCLOUD,
}

/**
 * What the user set up. The secret (the API key or app password) is not here: it lives encrypted, in [SecretStore].
 * Everything is **off** until the user turns the backup on, and nothing is sent anywhere before that.
 */
data class BackupSettings(
    /**
     * The one consent that lets eikon use the network at all, given (or refused) in the first-run screens and changeable in Settings. **Off until the user says yes.** While it is
     * off, nothing connects anywhere whatever else is configured: the schedule is cancelled, a run in progress stops, and no connection can be opened.
     */
    val networkAllowed: Boolean = false,
    val enabled: Boolean = false,
    val kind: ServerKind = ServerKind.NEXTCLOUD,
    /** `https://…` only. */
    val serverUrl: String = "",
    /** The Nextcloud login name (Immich needs none). */
    val username: String = "",
    /** Where the files go on a Nextcloud server, relative to the user's files. */
    val folder: String = DEFAULT_FOLDER,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val includeVideos: Boolean = true,
    /** Hidden photos are left out unless the user says otherwise: hiding them was a choice about who sees them. */
    val includeHidden: Boolean = false,
    /** SHA-256 (hex) of the one certificate the user agreed to trust for this server, when it is not signed by an authority the phone knows. */
    val pinnedCertificate: String? = null,
) {
    /** The backup may run: the user allowed the network **and** turned the backup on. Every place that would go online asks this, not [enabled]. */
    val active: Boolean get() = networkAllowed && enabled

    /** The address, the login and the way of trusting it: changing any of them means a different destination, so what was sent before says nothing about it. */
    val destination: String get() = listOf(kind.name, normalizedUrl(serverUrl), username.trim(), folder.trim()).joinToString("|")

    companion object {
        const val DEFAULT_FOLDER = "eikon"

        /** The address without a trailing slash and in lower case where case does not matter; blank if it is not an `https` address. */
        fun normalizedUrl(raw: String): String {
            val text = raw.trim().trimEnd('/')
            if (!text.startsWith("https://", ignoreCase = true) || text.length <= "https://".length) return ""
            val scheme = text.substring(0, "https://".length).lowercase()
            val rest = text.substring("https://".length)
            val host = rest.substringBefore('/')
            val path = rest.removePrefix(host)
            return scheme + host.lowercase() + path
        }
    }
}

/** A photo or video ready to be copied: what the server needs to know about it. */
data class BackupFile(
    val mediaId: Long,
    val name: String,
    val mimeType: String,
    val isVideo: Boolean,
    val takenAt: Long,
    val modifiedAt: Long,
    val isFavorite: Boolean,
    /** Exact size in bytes of what is sent. */
    val length: Long,
    /** SHA-1 of what is sent, in lower-case hex. */
    val sha1: String,
)

enum class UploadResult {
    /** The server now has it. */
    STORED,

    /** The server already had it (it recognised the file), so nothing was sent again. */
    ALREADY_THERE,
}

/** What a connection test found out. */
data class ServerCheck(val product: String, val version: String?, val warnings: List<String> = emptyList())

/**
 * Why a copy did not happen. The difference matters: a photo the server refuses is marked and left alone, but a server that cannot be reached, or that does not accept the
 * credential, says nothing about the photos, so the run stops there and tries again later without counting anything against them.
 */
sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** No connection, timeouts, or the server failing for now (5xx, too many requests, out of space). */
    class Unreachable(message: String, cause: Throwable? = null) : BackupException(message, cause)

    /** The server did not accept the API key or the password (401, 403). */
    class Unauthorized(message: String) : BackupException(message)

    /** The certificate is not signed by an authority the phone knows and is not the one the user agreed to. [fingerprint] is its SHA-256, when it could be read. */
    class Untrusted(message: String, val fingerprint: String? = null, cause: Throwable? = null) : BackupException(message, cause)

    /** The address is not a server of the kind chosen, or it redirects somewhere else (which is never followed). */
    class Misconfigured(message: String) : BackupException(message)

    /** The server refused this one file (too large, a type it does not take). */
    class Rejected(message: String) : BackupException(message)

    /** The user has not allowed eikon to use the network (or took the permission back), so nothing was opened. */
    class NetworkOff : BackupException("The network is off for eikon: allow it in Settings before connecting to a server.")
}

/** Where a backup goes. One implementation per [ServerKind]; the same runner drives both. */
interface BackupTarget {
    /** Reaches the server, checks the certificate and the credential, and says what it found. Throws [BackupException]. */
    suspend fun check(): ServerCheck

    /** Of [files], the ones the server does not have yet. A server that cannot tell in advance returns them all; [upload] then decides. */
    suspend fun missing(files: List<BackupFile>): List<BackupFile> = files

    /** Sends [file], reading its bytes from [open] (called once per attempt, and closed by the target). Throws [BackupException]. */
    suspend fun upload(file: BackupFile, open: () -> InputStream): UploadResult
}
