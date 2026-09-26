package app.eikon.gallery.data.indexing

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisPriorityTest {
    @Test
    fun startsWithNothingOnScreen() {
        assertEquals(emptyList<Long>(), AnalysisPriority().current())
    }

    @Test
    fun whatIsOnScreenReplacesWhatWasBefore() {
        val priority = AnalysisPriority()
        priority.show(listOf(1, 2, 3))
        priority.show(listOf(7, 8))
        assertEquals(listOf(7L, 8L), priority.current())
    }

    @Test
    fun aPhotoReportedTwiceCountsOnceAndKeepsItsPlace() {
        val priority = AnalysisPriority()
        priority.show(listOf(5, 6, 5, 7, 6))
        assertEquals(listOf(5L, 6L, 7L), priority.current())
    }

    @Test
    fun aVeryDenseScreenIsCutAtTheLimit() {
        val priority = AnalysisPriority()
        priority.show((1L..1000L).toList())
        assertEquals(AnalysisPriority.MAX, priority.current().size)
        assertEquals(1L, priority.current().first())
    }

    @Test
    fun leavingTheScreenForgetsEverything() {
        val priority = AnalysisPriority()
        priority.show(listOf(1, 2))
        priority.clear()
        assertEquals(emptyList<Long>(), priority.current())
    }
}
