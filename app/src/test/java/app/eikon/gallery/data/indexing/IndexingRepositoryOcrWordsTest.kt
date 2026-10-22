package app.eikon.gallery.data.indexing

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.DuplicatesDao
import app.eikon.gallery.data.db.IndexDao
import app.eikon.gallery.data.db.OcrWordEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Saving a photo's OCR words always replaces whatever was there before: a photo analysed again has no identity to keep from one run's words to the next. */
class IndexingRepositoryOcrWordsTest {
    private val calls = mutableListOf<List<Any?>>()

    private fun <T : Any> stub(type: Class<T>): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            calls += listOf(method.name) + (args ?: emptyArray()).filterNot { it is kotlin.coroutines.Continuation<*> }
            null
        })!!

    private val repository = IndexingRepository(stub(IndexDao::class.java), stub(DuplicatesDao::class.java), Clock { 0 }, AnalysisPriority())

    @Test
    fun savingWordsDeletesTheOldOnesFirstThenInsertsTheNewOnes() = runTest {
        val words = listOf(OcrWordEntity(mediaId = 42, line = 0, text = "Ciao", left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f))

        repository.saveOcrWords(42, words)

        assertEquals(listOf(listOf("deleteOcrWordsOf", 42L), listOf("insertOcrWords", words)), calls)
    }

    @Test
    fun savingNoWordsOnlyDeletesTheOldOnesAndInsertsNothing() = runTest {
        repository.saveOcrWords(42, emptyList())

        assertEquals(listOf(listOf("deleteOcrWordsOf", 42L)), calls)
    }
}
