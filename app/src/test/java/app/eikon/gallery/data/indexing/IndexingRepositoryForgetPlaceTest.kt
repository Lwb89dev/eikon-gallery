package app.eikon.gallery.data.indexing

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.DuplicatesDao
import app.eikon.gallery.data.db.IndexDao
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** After eikon changes where a photo was taken, what it had learned about the place is forgotten so the analysis reads it again from the file. */
class IndexingRepositoryForgetPlaceTest {
    private val calls = mutableListOf<List<Any?>>()

    private fun <T : Any> stub(type: Class<T>): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            calls += listOf(method.name) + (args ?: emptyArray()).filterNot { it is kotlin.coroutines.Continuation<*> }
            null
        })!!

    private val repository = IndexingRepository(stub(IndexDao::class.java), stub(DuplicatesDao::class.java), Clock { 0 }, AnalysisPriority())

    @Test
    fun theStoredPositionAndTheDoneMarkOfTheGeoStepGoAndNothingElse() = runTest {
        repository.forgetPlace(42)

        assertEquals(listOf(listOf("deleteGeo", listOf(42L)), listOf("deleteState", 42L, "GEO")), calls)
    }
}
