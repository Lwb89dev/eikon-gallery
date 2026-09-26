package app.eikon.gallery.data.backup

import android.content.Context
import android.os.PowerManager
import android.provider.MediaStore
import app.eikon.gallery.core.di.ApplicationScope
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.BackupDao
import app.eikon.gallery.data.db.BackupItemEntity
import app.eikon.gallery.data.db.toDomain
import app.eikon.gallery.domain.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The queue of the backup over the database: which photos are still to send, for the settings as they are now. */
class RoomBackupQueue @Inject constructor(
    private val dao: BackupDao,
    private val settings: BackupSettingsRepository,
    private val clock: Clock,
) : BackupQueue {
    override suspend fun pending(limit: Int): List<MediaItem> {
        val current = settings.current()
        return dao.pending(current.includeVideos, current.includeHidden, MAX_ATTEMPTS, limit).map { it.toDomain() }
    }

    override suspend fun markDone(item: MediaItem, file: BackupFile) {
        dao.put(BackupItemEntity(item.id, BackupItemEntity.STATUS_DONE, 0, item.modifiedAt, file.length, file.sha1, clock.nowMillis()))
    }

    override suspend fun markFailed(item: MediaItem) {
        val before = dao.get(item.id)
        // Attempts count in a row for the same version of the file; a file that changed starts again.
        val attempts = if (before != null && before.status == BackupItemEntity.STATUS_FAILED && before.modifiedAt == item.modifiedAt) before.attempts + 1 else 1
        dao.put(BackupItemEntity(item.id, BackupItemEntity.STATUS_FAILED, attempts, item.modifiedAt, item.sizeBytes, null, clock.nowMillis()))
    }

    companion object {
        /** A photo the server refused this many times in a row is left alone until the user asks for another go. */
        const val MAX_ATTEMPTS = 3
    }
}

/** How far the backup has got (of what the settings say to back up), for the settings screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupProgress @Inject constructor(private val dao: BackupDao, private val settings: BackupSettingsRepository) {
    val counts: Flow<app.eikon.gallery.data.db.BackupCounts> = settings.settings.flatMapLatest { s ->
        combine(
            dao.observeTotal(s.includeVideos, s.includeHidden),
            dao.observeDone(s.includeVideos, s.includeHidden),
            dao.observeFailed(s.includeVideos, s.includeHidden),
        ) { total, done, failed -> app.eikon.gallery.data.db.BackupCounts(total, done, failed) }
    }
}

/**
 * The bytes of a photo as they are on disk. Android hides where a photo was taken from apps that ask for it the ordinary way, so a backup made that way would silently lose
 * the location of every photo; with the "read photo locations" permission the original is asked for, and without it the copy is what the phone gives, which the settings say.
 */
class ContentResolverBackupSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val access: MediaAccessChecker,
) : BackupSource {
    override suspend fun fingerprint(item: MediaItem): Fingerprint = withContext(Dispatchers.IO) { open(item).use { digest(it) } }

    /** Reads [input] to its end, checking between reads that the run has not been cancelled. */
    private suspend fun digest(input: InputStream): Fingerprint {
        val sha1 = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(BUFFER)
        var length = 0L
        var read = input.read(buffer)
        while (read >= 0) {
            currentCoroutineContext().ensureActive()
            sha1.update(buffer, 0, read)
            length += read
            read = input.read(buffer)
        }
        return Fingerprint(length, sha1.digest().joinToString("") { "%02x".format(it) })
    }

    override fun open(item: MediaItem): InputStream {
        val uri = if (access.canReadLocation()) MediaStore.setRequireOriginal(item.uri) else item.uri
        return try {
            context.contentResolver.openInputStream(uri) ?: throw IOException("could not open ${item.displayName}")
        } catch (e: FileNotFoundException) {
            throw IOException("${item.displayName} is gone", e)
        } catch (e: SecurityException) {
            throw IOException("no access to ${item.displayName}", e)
        }
    }

    private companion object {
        const val BUFFER = 64 * 1024
    }
}

/** What the runner asks the device: the settings now, Battery Saver, heat and the clock. */
@Singleton
class DeviceBackupEnvironment @Inject constructor(
    @ApplicationContext context: Context,
    private val settings: BackupSettingsRepository,
    private val clock: Clock,
    @ApplicationScope scope: CoroutineScope,
) : BackupEnvironment {
    private val power = context.getSystemService(PowerManager::class.java)

    @Volatile
    private var latest = BackupSettings()

    init {
        // The runner asks for the settings between photos; keeping them here means it sees a change the user makes mid-run without touching storage each time.
        scope.launch { settings.settings.collect { latest = it } }
    }

    /** Makes sure the settings are the current ones before a run starts (the collection above may not have delivered the first value yet). */
    suspend fun refresh() {
        latest = settings.current()
    }

    override fun settings() = latest

    override fun thermalStatus(): Int = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE

    override fun isPowerSaveMode(): Boolean = power?.isPowerSaveMode ?: false

    override fun nowMillis(): Long = clock.nowMillis()
}
