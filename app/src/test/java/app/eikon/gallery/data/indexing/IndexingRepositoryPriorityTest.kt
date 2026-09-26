package app.eikon.gallery.data.indexing

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.DuplicatesDao
import app.eikon.gallery.data.db.IndexDao
import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.db.MediaEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the queue puts the photos on screen ahead of the rest, on stand-ins for the database. */
class IndexingRepositoryPriorityTest {
    private val calls = mutableListOf<String>()
    private var newest: List<MediaEntity> = emptyList()
    private var onScreen: List<MediaEntity> = emptyList()
    private val priority = AnalysisPriority()

    private fun photo(id: Long) = MediaEntity(id, "f$id", "image/jpeg", false, id, id, id, 1, 1, 0, 1, null, null, false, false, false, false, false)

    private fun <T : Any> stub(type: Class<T>, answers: (String, Array<Any?>) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            calls += method.name
            answers(method.name, args ?: emptyArray())
        })!!

    private val indexDao = stub(IndexDao::class.java) { name, _ ->
        when (name) {
            "pending" -> newest
            "pendingAmong" -> onScreen
            else -> error("unexpected $name")
        }
    }
    private val duplicatesDao = stub(DuplicatesDao::class.java) { name, _ -> error("unexpected $name") }

    private fun repository() = IndexingRepository(indexDao, duplicatesDao, Clock { 0 }, priority)

    // Room's suspend functions take a continuation, which the proxy cannot answer; the queue is exercised through its blocking equivalent below.
    private suspend fun pending(limit: Int) = repository().pending(IndexStage.OCR, limit)

    @Test
    fun withNothingOnScreenTheQueueIsJustNewestFirst() = runTest {
        newest = listOf(photo(9), photo(8), photo(7))

        assertEquals(listOf(9L, 8L, 7L), pendingIds(3))
        assertTrue("pendingAmong" !in calls)
    }

    @Test
    fun thePhotosOnScreenComeBeforeTheNewest() = runTest {
        priority.show(listOf(2, 3))
        onScreen = listOf(photo(3), photo(2))
        newest = listOf(photo(9), photo(8), photo(7))

        assertEquals(listOf(3L, 2L, 9L), pendingIds(3))
    }

    @Test
    fun aPhotoBothOnScreenAndNewestIsNotListedTwice() = runTest {
        priority.show(listOf(9))
        onScreen = listOf(photo(9))
        newest = listOf(photo(9), photo(8), photo(7))

        assertEquals(listOf(9L, 8L, 7L), pendingIds(3))
    }

    @Test
    fun aScreenFullOfPendingPhotosFillsTheBatchAndTheRestIsNotAskedFor() = runTest {
        priority.show(listOf(1, 2, 3, 4))
        onScreen = listOf(photo(4), photo(3), photo(2), photo(1))

        assertEquals(listOf(4L, 3L), pendingIds(2))
        assertTrue("pending" !in calls)
    }

    @Test
    fun whatIsOnScreenButAlreadyDoneAddsNothing() = runTest {
        priority.show(listOf(5))
        onScreen = emptyList()
        newest = listOf(photo(9), photo(8))

        assertEquals(listOf(9L, 8L), pendingIds(2))
    }

    private suspend fun pendingIds(limit: Int): List<Long> = pending(limit).map { it.id }
}
