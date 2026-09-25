package app.eikon.gallery.data.embedding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdleCacheTest {
    private class Resource : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun usesInsideTheIdleWindowShareOneInstance() = runTest {
        val created = mutableListOf<Resource>()
        val cache = IdleCache(this, 1_000) { Resource().also(created::add) }

        cache.use { }
        advanceTimeBy(500)
        cache.use { }

        assertEquals(1, created.size)
        assertFalse(created.single().closed)
        cache.close()
    }

    @Test
    fun theInstanceIsClosedAfterTheIdleTimeAndRecreatedOnNextUse() = runTest {
        val created = mutableListOf<Resource>()
        val cache = IdleCache(this, 1_000) { Resource().also(created::add) }

        cache.use { }
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(created.single().closed)

        cache.use { }
        assertEquals(2, created.size)
        cache.close()
    }

    @Test
    fun aUseKeepsTheInstanceAliveLongerThanTheFirstTimeout() = runTest {
        val created = mutableListOf<Resource>()
        val cache = IdleCache(this, 1_000) { Resource().also(created::add) }

        cache.use { }
        advanceTimeBy(800)
        cache.use { }
        advanceTimeBy(800)
        runCurrent()

        assertFalse(created.single().closed)
        cache.close()
        assertTrue(created.single().closed)
    }
}
