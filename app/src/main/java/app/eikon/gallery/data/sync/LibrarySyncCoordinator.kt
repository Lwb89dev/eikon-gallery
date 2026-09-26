package app.eikon.gallery.data.sync

import android.util.Log
import app.eikon.gallery.core.di.ApplicationScope
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.data.mediastore.MediaStoreChangeMonitor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val done: Int, val total: Int) : SyncStatus
    data object NoAccess : SyncStatus
    data object Failed : SyncStatus
}

/**
 * Serialises library syncs: any number of requests while one is running collapse into one follow-up
 * run. Change observation only happens while the UI is visible ([keepFresh]), so eikon never wakes
 * the device in the background to re-index.
 */
@Singleton
class LibrarySyncCoordinator @Inject constructor(
    private val syncer: MediaSyncer,
    private val monitor: MediaStoreChangeMonitor,
    private val accessChecker: MediaAccessChecker,
    @ApplicationScope scope: CoroutineScope,
) {
    // Starts as "working": until the first sync finishes an empty index means "not loaded yet", not "empty".
    private val mutableStatus = MutableStateFlow<SyncStatus>(SyncStatus.Running(done = 0, total = 0))
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val forceNext = AtomicBoolean(false)

    init {
        scope.launch {
            for (request in requests) runSync(force = forceNext.getAndSet(false))
        }
    }

    /** [force] re-reads the whole library, e.g. after the user picked different photos. */
    fun requestSync(force: Boolean = false) {
        if (force) forceNext.set(true)
        requests.trySend(Unit)
    }

    /** Suspends while collecting: sync now, then again whenever MediaStore changes. */
    @OptIn(FlowPreview::class)
    suspend fun keepFresh() {
        requestSync()
        monitor.changes().debounce(CHANGE_DEBOUNCE_MS).collect { requestSync() }
    }

    private suspend fun runSync(force: Boolean) {
        mutableStatus.value = SyncStatus.Running(done = 0, total = 0)
        mutableStatus.value = try {
            val result = syncer.sync(accessChecker.current(), force) { done, total ->
                mutableStatus.value = SyncStatus.Running(done, total)
            }
            if (result == SyncResult.NoAccess) SyncStatus.NoAccess else SyncStatus.Idle
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Only the kind of error: its message (an SQL statement, a file address) can name what is in the library.
            Log.w(TAG, "Library sync failed: ${error.javaClass.simpleName}")
            SyncStatus.Failed
        }
    }

    private companion object {
        const val TAG = "LibrarySync"
        const val CHANGE_DEBOUNCE_MS = 1500L
    }
}
