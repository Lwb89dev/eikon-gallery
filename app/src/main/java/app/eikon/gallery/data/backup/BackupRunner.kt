package app.eikon.gallery.data.backup

import app.eikon.gallery.data.indexing.ThermalAction
import app.eikon.gallery.data.indexing.ThermalPolicy
import app.eikon.gallery.domain.MediaItem
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The size and SHA-1 of what would be sent for one photo. */
data class Fingerprint(val length: Long, val sha1: String)

/** The photos still to send, and the way the result is written down. */
interface BackupQueue {
    /** The next photos to send, newest first, for the settings as they are now. */
    suspend fun pending(limit: Int): List<MediaItem>

    suspend fun markDone(item: MediaItem, file: BackupFile)

    /** The server refused this photo (or it could not be read): counts against it, and after a few times it is left alone until the user asks again. */
    suspend fun markFailed(item: MediaItem)
}

/** Where the bytes of a photo come from. */
interface BackupSource {
    /** Reads the photo once to find its exact size and SHA-1. Throws [IOException] if it cannot be read. */
    suspend fun fingerprint(item: MediaItem): Fingerprint

    /** The photo's bytes, to be closed by the caller. */
    fun open(item: MediaItem): InputStream
}

/** What the runner needs to know about the world; separate so tests can drive every situation. */
interface BackupEnvironment {
    fun settings(): BackupSettings
    fun thermalStatus(): Int
    fun isPowerSaveMode(): Boolean
    fun nowMillis(): Long
}

/** Why a run ended before the queue was empty. */
enum class BackupStop {
    /** The time slice is used up; the next run continues. */
    TIME_UP,

    /** The user turned the backup off, or the settings no longer name a server, while it ran. */
    DISABLED,

    /** Battery Saver is on and the run was not asked for by the user. */
    POWER_SAVE,

    /** The phone is too warm. */
    THERMAL,

    /** The server could not be reached, or failed for now. */
    UNREACHABLE,

    /** The server did not accept the credential. */
    UNAUTHORIZED,

    /** The server's certificate is not one the phone or the user trusts. */
    UNTRUSTED,

    /** The address is wrong for the kind of server chosen. */
    MISCONFIGURED,

    /** The server refused a run of photos one after the other: that says something about the server or its limits, not about the photos, so it stops rather than mark them all. */
    TOO_MANY_REFUSED,
}

data class BackupReport(val sent: Int, val alreadyThere: Int, val refused: Int, val stoppedBy: BackupStop?, val message: String? = null)

/**
 * Copies photos to the server, a few at a time, for a limited time slice. Everything that goes wrong for one photo is contained: the server refusing it is written down
 * against that photo and the run goes on. Everything that says something about the *server* (not reachable, not accepting the credential, a certificate nobody agreed to)
 * ends the run at once and counts against nothing, so a night without Wi-Fi does not use up the photos' chances. Nothing is ever deleted from the server, and nothing is
 * sent twice: what the server already has is recognised and only written down.
 */
class BackupRunner(
    private val queue: BackupQueue,
    private val source: BackupSource,
    private val environment: BackupEnvironment,
    private val target: BackupTarget,
) {
    private var sent = 0
    private var alreadyThere = 0
    private var refused = 0

    /** Photos refused during this run: not offered again until the next one. */
    private val refusedNow = mutableSetOf<Long>()

    suspend fun run(budgetMs: Long, manual: Boolean = false): BackupReport {
        val deadline = environment.nowMillis() + budgetMs
        return try {
            work(deadline, manual)
            report(null)
        } catch (stop: Stopped) {
            report(stop.reason, stop.message)
        }
    }

    private suspend fun work(deadline: Long, manual: Boolean) {
        while (true) {
            val batch = queue.pending(BATCH + refusedNow.size).filterNot { it.id in refusedNow }.take(BATCH)
            if (batch.isEmpty()) return
            val ready = batch.mapNotNull { item -> fingerprinted(item, deadline, manual) }
            val missing = ask { target.missing(ready.map { it.second }) }.map { it.mediaId }.toSet()
            for ((item, file) in ready) settle(item, file, isMissing = file.mediaId in missing, deadline, manual)
        }
    }

    /** What the server already has is written down; the rest is sent. */
    private suspend fun settle(item: MediaItem, file: BackupFile, isMissing: Boolean, deadline: Long, manual: Boolean) {
        if (isMissing) {
            check(deadline, manual)
            send(item, file)
        } else {
            queue.markDone(item, file)
            alreadyThere++
        }
    }

    private suspend fun fingerprinted(item: MediaItem, deadline: Long, manual: Boolean): Pair<MediaItem, BackupFile>? {
        check(deadline, manual)
        val print = try {
            source.fingerprint(item)
        } catch (_: IOException) {
            refuse(item)
            return null
        }
        return item to BackupFile(item.id, item.displayName, item.mimeType, item.isVideo, item.takenAt, item.modifiedAt, item.isFavorite, print.length, print.sha1)
    }

    private suspend fun send(item: MediaItem, file: BackupFile) {
        try {
            val result = ask { target.upload(file) { source.open(item) } }
            queue.markDone(item, file)
            if (result == UploadResult.STORED) sent++ else alreadyThere++
        } catch (rejected: BackupException.Rejected) {
            refuse(item)
        } catch (unreadable: IOException) {
            refuse(item)
        }
    }

    private suspend fun refuse(item: MediaItem) {
        queue.markFailed(item)
        refused++
        refusedNow += item.id
        if (refusedNow.size >= MAX_REFUSED_PER_RUN) throw Stopped(BackupStop.TOO_MANY_REFUSED, null)
    }

    /** Runs a call on the server, turning what says something about the server rather than about a photo into the end of the run. */
    private suspend fun <T> ask(call: suspend () -> T): T = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: BackupException.Unreachable) {
        throw Stopped(BackupStop.UNREACHABLE, e.message)
    } catch (e: BackupException.Unauthorized) {
        throw Stopped(BackupStop.UNAUTHORIZED, e.message)
    } catch (e: BackupException.Untrusted) {
        throw Stopped(BackupStop.UNTRUSTED, e.message)
    } catch (e: BackupException.Misconfigured) {
        throw Stopped(BackupStop.MISCONFIGURED, e.message)
    }

    private suspend fun check(deadline: Long, manual: Boolean) {
        currentCoroutineContext().ensureActive()
        val settings = environment.settings()
        val reason = when {
            !settings.enabled -> BackupStop.DISABLED
            !manual && environment.isPowerSaveMode() -> BackupStop.POWER_SAVE
            ThermalPolicy.decide(environment.thermalStatus()) == ThermalAction.STOP -> BackupStop.THERMAL
            environment.nowMillis() >= deadline -> BackupStop.TIME_UP
            else -> null
        }
        if (reason != null) throw Stopped(reason, null)
    }

    private fun report(stoppedBy: BackupStop?, message: String? = null) = BackupReport(sent, alreadyThere, refused, stoppedBy, message)

    private class Stopped(val reason: BackupStop, override val message: String?) : Exception(message)

    companion object {
        const val BATCH = 20
        const val MAX_REFUSED_PER_RUN = 25
    }
}
