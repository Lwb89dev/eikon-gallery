package app.eikon.gallery.data.embedding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps something expensive to create (a 135 MB model session) alive between uses, and frees it after
 * [idleMillis] without use. A search box is used in bursts, and creating the session takes seconds, so
 * it stays loaded while the user types and is released soon after they stop.
 */
class IdleCache<T : AutoCloseable>(
    private val scope: CoroutineScope,
    private val idleMillis: Long,
    private val create: () -> T,
) {
    private val lock = Mutex()
    private var value: T? = null
    private var closer: Job? = null

    /** Runs [block] with the cached value, creating it if needed. Uses are serialised. */
    suspend fun <R> use(block: (T) -> R): R = lock.withLock {
        closer?.cancel()
        val instance = value ?: create().also { value = it }
        try {
            block(instance)
        } finally {
            closer = scope.launch { closeAfterIdle() }
        }
    }

    private suspend fun closeAfterIdle() {
        delay(idleMillis)
        close()
    }

    suspend fun close() = lock.withLock {
        value?.close()
        value = null
    }
}
