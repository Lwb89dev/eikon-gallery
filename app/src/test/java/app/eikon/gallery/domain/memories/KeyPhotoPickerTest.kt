package app.eikon.gallery.domain.memories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPhotoPickerTest {
    private fun photo(id: Long, at: Long = id * 60_000, mp: Int = 12, favorite: Boolean = false, faces: Int = 0) =
        KeyCandidate(id, at, width = 4000, height = mp * 250, isFavorite = favorite, faces = faces)

    @Test
    fun aSmallMemoryShowsEverythingInOrderOfTime() {
        val picked = KeyPhotoPicker.pick(listOf(photo(3), photo(1), photo(2)), max = 10)
        assertEquals(listOf(1L, 2L, 3L), picked)
    }

    @Test
    fun aBigMemoryIsSpreadAcrossItsWholeTimeSpan() {
        val photos = (1L..100L).map { photo(it) }
        val picked = KeyPhotoPicker.pick(photos, max = 10)

        assertEquals(10, picked.size)
        assertTrue("first from the start, last from the end: $picked", picked.first() <= 10 && picked.last() >= 91)
        assertEquals(picked.sorted(), picked)
    }

    @Test
    fun withinEachStretchTheFavoriteWinsOverABiggerPhoto() {
        val photos = listOf(photo(1, mp = 12), photo(2, mp = 2, favorite = true), photo(3, mp = 12), photo(4, mp = 12))
        assertEquals(listOf(2L, 3L), KeyPhotoPicker.pick(photos, max = 2)) // the second stretch has no favorite: the first of two equals
    }

    @Test
    fun peopleAndSizeBreakTiesWhenNothingIsFavorite() {
        val small = photo(1, mp = 2)
        val big = photo(2, mp = 12)
        val withPeople = photo(3, mp = 12, faces = 2)
        assertTrue(KeyPhotoPicker.score(big) > KeyPhotoPicker.score(small))
        assertTrue(KeyPhotoPicker.score(withPeople) > KeyPhotoPicker.score(big))
        assertTrue(KeyPhotoPicker.score(photo(4, mp = 1, favorite = true)) > KeyPhotoPicker.score(withPeople))
    }

    @Test
    fun nothingToPickGivesNothing() {
        assertTrue(KeyPhotoPicker.pick(emptyList(), 5).isEmpty())
        assertTrue(KeyPhotoPicker.pick(listOf(photo(1)), 0).isEmpty())
    }
}
