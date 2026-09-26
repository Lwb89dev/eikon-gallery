package app.eikon.gallery.data.embedding

import app.eikon.gallery.data.db.EmbeddingRow
import app.eikon.gallery.data.db.EmbeddingStats
import java.lang.ref.SoftReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Holds the matrix of every stored vector and keeps it up to date. While analysis is running it stores vectors all the time, and reading 100,000 of them back (50 MB, decrypted, through
 * the database the list is also paging from) for each search made the search slow and the screen with it. So a vector saved by this process is remembered ([saved]) and, when the
 * matrix is asked for, only those are read and put into the copy already held. What the database says about itself ([stats]) is compared with the result: if a vector was
 * removed, or anything else changed behind this class's back, they differ and the whole set is read again, as before.
 *
 * The copy is a soft reference: 100,000 photos are about 50 MB, worth keeping while the user searches but not worth an out-of-memory error.
 */
class MatrixKeeper(
    private val stats: suspend () -> EmbeddingStats,
    private val rowsOf: suspend (ids: List<Long>) -> List<EmbeddingRow>,
    private val readAll: suspend () -> EmbeddingMatrix,
) {
    private val lock = Mutex()
    private val savedIds = ConcurrentHashMap.newKeySet<Long>()
    private var held: SoftReference<Held>? = null

    private class Held(val matrix: EmbeddingMatrix, val size: Int, val idSum: Long)

    /** A vector of [mediaId] was stored (new, or replacing the one before). */
    fun saved(mediaId: Long) {
        savedIds += mediaId
    }

    /** How many times the whole set was read, for the tests and for anyone wondering why a search was slow. */
    @Volatile
    var fullReads = 0
        private set

    suspend fun matrix(): EmbeddingMatrix = lock.withLock {
        val changed = takeSaved()
        val now = stats()
        val current = held?.get()
        if (current != null && changed.isEmpty() && current.size == now.count && current.idSum == now.idSum) return current.matrix
        val updated = current?.let { patched(it, changed, now) } ?: readEverything()
        held = SoftReference(Held(updated, updated.size, updated.idSum))
        updated
    }

    private fun takeSaved(): List<Long> = savedIds.toList().also { savedIds.removeAll(it.toSet()) }

    /** The held matrix with [changed] read into it, or null if the database does not agree with the result (something else was added or removed). */
    private suspend fun patched(current: Held, changed: List<Long>, now: EmbeddingStats): EmbeddingMatrix? {
        val rows = changed.chunked(CHUNK).flatMap { rowsOf(it) }
        val result = current.matrix.withRows(rows)
        return result.takeIf { it.size == now.count && it.idSum == now.idSum }
    }

    private suspend fun readEverything(): EmbeddingMatrix {
        fullReads++
        return readAll()
    }

    private companion object {
        /** Below SQLite's limit on bound values. */
        const val CHUNK = 500
    }
}
