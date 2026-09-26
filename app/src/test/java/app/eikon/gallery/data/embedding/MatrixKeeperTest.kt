package app.eikon.gallery.data.embedding

import app.eikon.gallery.data.db.EmbeddingRow
import app.eikon.gallery.data.db.EmbeddingStats
import java.util.TreeMap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The matrix follows what analysis stores without being read again in full, and still notices what it cannot follow. */
class MatrixKeeperTest {
    /** A database of vectors, read the way the real one is: in id order, in whole or by id. */
    private class Store {
        val rows = TreeMap<Long, ByteArray>()
        var rowsAsked = 0

        fun put(id: Long, marker: Int) {
            rows[id] = ByteArray(Embeddings.DIMENSIONS) { marker.toByte() }
        }

        fun stats() = EmbeddingStats(rows.size, rows.keys.sum())

        fun rowsOf(ids: List<Long>): List<EmbeddingRow> = ids.mapNotNull { id -> rows[id]?.let { EmbeddingRow(id, it) } }.also { rowsAsked += it.size }

        fun readAll(): EmbeddingMatrix {
            val builder = EmbeddingMatrix.Builder(rows.size)
            rows.forEach { (id, vector) -> builder.add(id, vector) }
            return builder.build()
        }
    }

    private val store = Store()
    private val keeper = MatrixKeeper({ store.stats() }, { store.rowsOf(it) }, { store.readAll() })

    private fun save(id: Long, marker: Int) {
        store.put(id, marker)
        keeper.saved(id)
    }

    private fun EmbeddingMatrix.ids() = (0 until size).map { idAt(it) }

    @Test
    fun theFirstAskReadsEverythingAndTheSecondReadsNothing() = runTest {
        (1L..5L).forEach { store.put(it, it.toInt()) }

        val first = keeper.matrix()
        val second = keeper.matrix()

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), first.ids())
        assertSame(first, second)
        assertEquals(1, keeper.fullReads)
    }

    @Test
    fun vectorsStoredSinceAreReadInWithoutReadingTheRest() = runTest {
        listOf(10L, 20L, 30L).forEach { store.put(it, 1) }
        keeper.matrix()
        store.rowsAsked = 0

        save(25, 2) // between two held ones
        save(5, 3) // before all of them
        save(40, 4) // after all of them
        val updated = keeper.matrix()

        assertEquals(listOf(5L, 10L, 20L, 25L, 30L, 40L), updated.ids())
        assertEquals("only the three new rows were read", 3, store.rowsAsked)
        assertEquals("the whole set was read once, at the start", 1, keeper.fullReads)
    }

    @Test
    fun aReplacedVectorTakesThePlaceOfTheOldOneWithoutChangingTheCount() = runTest {
        listOf(1L, 2L, 3L).forEach { store.put(it, 1) }
        val before = keeper.matrix()

        save(2, 9)
        val after = keeper.matrix()

        assertEquals(before.ids(), after.ids())
        assertEquals(1, keeper.fullReads)
        // Photo 2 now has the vector of 9s and photo 1 that of 1s: their similarity is the sum of the products, 9 * 1 * 512 / 127^2.
        assertEquals(9f * Embeddings.DIMENSIONS / (127f * 127f), after.similarity(0, 1), 0.001f)
    }

    @Test
    fun aVectorRemovedBehindItsBackIsNoticedAndEverythingIsReadAgain() = runTest {
        listOf(1L, 2L, 3L).forEach { store.put(it, 1) }
        keeper.matrix()

        store.rows.remove(2L) // a photo left the library: nobody told the keeper
        val updated = keeper.matrix()

        assertEquals(listOf(1L, 3L), updated.ids())
        assertEquals(2, keeper.fullReads)
    }

    @Test
    fun aRemovalAlongsideASaveIsStillNoticed() = runTest {
        listOf(1L, 2L, 3L).forEach { store.put(it, 1) }
        keeper.matrix()

        store.rows.remove(2L)
        save(4, 1) // the count is 3 again, but the ids are not the ones held plus the new one
        val updated = keeper.matrix()

        assertEquals(listOf(1L, 3L, 4L), updated.ids())
        assertEquals(2, keeper.fullReads)
    }

    @Test
    fun anEmptyLibraryGivesAnEmptyMatrixAndThenGrows() = runTest {
        assertEquals(0, keeper.matrix().size)
        save(7, 1)
        assertEquals(listOf(7L), keeper.matrix().ids())
    }

    // --- the matrix itself --------------------------------------------------------------------------

    @Test
    fun withRowsKeepsIdOrderReplacesAndInsertsAndTakesTheLastOfADuplicate() {
        fun row(id: Long, marker: Int) = EmbeddingRow(id, ByteArray(Embeddings.DIMENSIONS) { marker.toByte() })
        val builder = EmbeddingMatrix.Builder(3)
        listOf(2L, 4L, 6L).forEach { builder.add(it, ByteArray(Embeddings.DIMENSIONS) { 1 }) }
        val matrix = builder.build()

        val result = matrix.withRows(listOf(row(5, 5), row(4, 7), row(1, 1), row(5, 6)))

        assertEquals(listOf(1L, 2L, 4L, 5L, 6L), result.ids())
        assertEquals("the untouched matrix is not changed", listOf(2L, 4L, 6L), matrix.ids())
        assertEquals(18L, result.idSum)
        // photo 5 took its last vector (6), photo 4 the replacement (7)
        assertEquals(6f * 6f * Embeddings.DIMENSIONS / (127f * 127f), result.similarity(3, 3), 0.001f)
        assertEquals(7f * 7f * Embeddings.DIMENSIONS / (127f * 127f), result.similarity(2, 2), 0.001f)
    }

    @Test
    fun withRowsOfNothingIsTheSameMatrix() {
        val matrix = EmbeddingMatrix.Builder(1).apply { add(1, ByteArray(Embeddings.DIMENSIONS)) }.build()
        assertSame(matrix, matrix.withRows(emptyList()))
    }

    @Test
    fun aBuilderThatIsExactlyFullHandsItsArraysOverInsteadOfCopyingThem() {
        val vector = ByteArray(Embeddings.DIMENSIONS) { 3 }
        val matrix = EmbeddingMatrix.Builder(2).apply { add(1, vector); add(2, vector) }.build()
        assertEquals(2, matrix.size)
        val short = EmbeddingMatrix.Builder(5).apply { add(1, vector) }.build()
        assertEquals("a builder that is not full is trimmed", 1, short.size)
        assertArrayEquals(longArrayOf(1L), longArrayOf(short.idAt(0)))
    }
}
