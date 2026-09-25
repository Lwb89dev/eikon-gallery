package app.eikon.gallery.data.places

import app.eikon.gallery.data.db.PlaceGroupRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceTreeTest {
    private val names = object : PlaceNames {
        override fun country(code: String) = mapOf("IT" to "Italia", "FR" to "Francia")[code] ?: code
        override fun region(key: String) = mapOf("IT.07" to "Lazio", "IT.09" to "Lombardia", "FR.11" to "Île-de-France")[key]
        override fun city(id: Long) = mapOf(1L to "Roma", 2L to "Latina", 3L to "Milano", 4L to "Parigi")[id]
    }

    private fun row(country: String?, region: String?, city: Long?, count: Int, media: Long, takenAt: Long) =
        PlaceGroupRow(country, region, city, count, media, media * 10, takenAt)

    private val rows = listOf(
        row("IT", "IT.07", 1, 50, media = 11, takenAt = 500),
        row("IT", "IT.07", 2, 5, media = 12, takenAt = 900),
        row("IT", "IT.09", 3, 20, media = 13, takenAt = 100),
        row("FR", "FR.11", 4, 90, media = 14, takenAt = 300),
        row(null, null, null, 3, media = 15, takenAt = 50),
    )

    @Test
    fun countriesRegionsAndCitiesAreNestedAndSortedByPhotoCount() {
        val tree = PlaceTree.build(rows, names)

        assertEquals(listOf("Francia", "Italia", ""), tree.map { it.title }) // 90, 75, then the unnamed group last
        val italy = tree.first { it.title == "Italia" }
        assertEquals(75, italy.photoCount)
        assertEquals(listOf("Lazio", "Lombardia"), italy.children.map { it.title })
        assertEquals(listOf("Roma", "Latina"), italy.children.first().children.map { it.title })
        assertEquals(PlaceKey.Country("IT"), italy.key)
        assertEquals(PlaceKey.Region("IT.07"), italy.children.first().key)
        assertEquals(PlaceKey.City(1), italy.children.first().children.first().key)
    }

    @Test
    fun aCountryOrRegionIsPicturedByTheNewestPhotoInsideIt() {
        val italy = PlaceTree.build(rows, names).first { it.title == "Italia" }
        assertEquals(12L, italy.cover.mediaId) // Latina's, taken at 900
        assertEquals(12L, italy.children.first().cover.mediaId)
        assertEquals(13L, italy.children.last().cover.mediaId)
    }

    @Test
    fun photosTooFarFromAnyCityFormOneUnnamedGroupWithoutChildren() {
        val unknown = PlaceTree.build(rows, names).last()
        assertEquals(PlaceKey.Unknown, unknown.key)
        assertEquals(3, unknown.photoCount)
        assertTrue(unknown.children.isEmpty())
    }

    @Test
    fun noPhotosGiveAnEmptyTree() {
        assertTrue(PlaceTree.build(emptyList(), names).isEmpty())
    }

    @Test
    fun aRegionMissingFromThePlaceFilesFallsBackToItsBiggestCity() {
        val tree = PlaceTree.build(listOf(row("IT", "IT.99", 1, 4, media = 1, takenAt = 1)), names)
        assertEquals("Roma", tree.single().children.single().title)
    }
}
