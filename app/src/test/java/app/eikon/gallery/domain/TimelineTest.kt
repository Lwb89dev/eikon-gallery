package app.eikon.gallery.domain

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineLayoutTest {
    // Grid positions:  0=H(a) 1 2 3 | 4=H(b) 5 | 6=H(c) 7 8
    private val layout = TimelineLayout.fromCounts(listOf("2025-08-14" to 3, "2025-08-13" to 1, "2025-08-12" to 2))

    @Test
    fun totalsIncludeOneHeaderPerSection() {
        assertEquals(6, layout.mediaCount)
        assertEquals(9, layout.gridItemCount)
    }

    @Test
    fun sectionsGetCumulativeStartIndexes() {
        assertEquals(listOf(0, 3, 4), layout.sections.map { it.startIndex })
    }

    @Test
    fun mediaIndexMapsToGridPositionSkippingHeaders() {
        assertEquals(listOf(1, 2, 3, 5, 7, 8), (0..5).map(layout::gridPositionOfMedia))
    }

    @Test
    fun gridPositionMapsBackToMediaIndexAndNullForHeaders() {
        val media = (0..8).map(layout::mediaIndexOfGridPosition)
        assertEquals(listOf(null, 0, 1, 2, null, 3, null, 4, 5), media)
    }

    @Test
    fun sectionLookupsAgreeInBothIndexSpaces() {
        assertEquals(listOf(0, 0, 0, 1, 2, 2), (0..5).map(layout::sectionIndexOfMedia))
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 2, 2, 2), (0..8).map(layout::sectionIndexOfGridPosition))
    }

    @Test
    fun outOfRangeLookupsAreRejected() {
        assertEquals(-1, layout.sectionIndexOfMedia(-1))
        assertEquals(-1, layout.sectionIndexOfMedia(6))
        assertEquals(-1, layout.gridPositionOfMedia(6))
        assertEquals(-1, layout.sectionIndexOfGridPosition(9))
        assertNull(layout.mediaIndexOfGridPosition(99))
    }

    @Test
    fun emptyTimelineHasNothing() {
        assertEquals(0, TimelineLayout.Empty.gridItemCount)
        assertEquals(-1, TimelineLayout.Empty.gridPositionOfMedia(0))
    }

    @Test
    fun roundTripHoldsForALargeLibrary() {
        val big = TimelineLayout.fromCounts((1..3000).map { "d$it" to (it % 37 + 1) })
        for (mediaIndex in 0 until big.mediaCount step 997) {
            val position = big.gridPositionOfMedia(mediaIndex)
            assertEquals(mediaIndex, big.mediaIndexOfGridPosition(position))
        }
    }
}

class TimelineLabelFormatterTest {
    private val formatter = TimelineLabelFormatter(
        today = LocalDate.of(2026, 9, 25),
        locale = Locale.ENGLISH,
        todayLabel = "Today",
        yesterdayLabel = "Yesterday",
        skeletonPatterns = {
            when (it) {
                "EEEEdMMMM" -> "EEEE d MMMM"
                "yMMMMd" -> "d MMMM yyyy"
                else -> "MMMM yyyy"
            }
        },
    )

    @Test
    fun todayAndYesterdayAreNamed() {
        assertEquals("Today", formatter.label("2026-09-25", TimelineGrouping.DAY))
        assertEquals("Yesterday", formatter.label("2026-09-24", TimelineGrouping.DAY))
    }

    @Test
    fun dayInCurrentYearOmitsTheYear() {
        assertEquals("Thursday 14 May", formatter.label("2026-05-14", TimelineGrouping.DAY))
    }

    @Test
    fun dayInAnotherYearIncludesIt() {
        assertEquals("14 August 2025", formatter.label("2025-08-14", TimelineGrouping.DAY))
    }

    @Test
    fun monthGroupingShowsMonthAndYear() {
        assertEquals("August 2025", formatter.label("2025-08", TimelineGrouping.MONTH))
    }

    @Test
    fun monthLabelAcceptsDayBuckets() {
        assertEquals("August 2025", formatter.monthLabel("2025-08-14"))
    }

    @Test
    fun unparseableBucketFallsBackToItsText() {
        assertEquals("0000", formatter.label("0000", TimelineGrouping.DAY))
        assertEquals("0000", formatter.label("0000", TimelineGrouping.MONTH))
        assertEquals("0000", formatter.monthLabel("0000"))
    }
}
