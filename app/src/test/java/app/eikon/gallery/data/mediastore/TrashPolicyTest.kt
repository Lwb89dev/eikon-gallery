package app.eikon.gallery.data.mediastore

import org.junit.Assert.assertEquals
import org.junit.Test

class TrashPolicyTest {
    private val day = 86_400_000L
    private val now = 1_000_000_000_000L

    @Test
    fun wholeDaysRemainExactly() {
        assertEquals(30, TrashPolicy.daysLeft(now + 30 * day, now))
        assertEquals(1, TrashPolicy.daysLeft(now + day, now))
    }

    @Test
    fun partialDaysRoundUpSoTheLastDayShowsOne() {
        assertEquals(1, TrashPolicy.daysLeft(now + 1, now))
        assertEquals(2, TrashPolicy.daysLeft(now + day + 1, now))
        assertEquals(29, TrashPolicy.daysLeft(now + 29 * day - 1, now))
    }

    @Test
    fun expiredItemsShowZeroNeverNegative() {
        assertEquals(0, TrashPolicy.daysLeft(now, now))
        assertEquals(0, TrashPolicy.daysLeft(now - 5 * day, now))
    }
}
