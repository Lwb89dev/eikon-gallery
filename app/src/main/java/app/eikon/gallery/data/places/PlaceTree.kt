package app.eikon.gallery.data.places

import app.eikon.gallery.data.db.PlaceGroupRow

/** What a place is called on screen. Built from the bundled place data and the device language. */
interface PlaceNames {
    fun country(code: String): String
    fun region(key: String): String?
    fun city(id: Long): String?
}

/** Which place a row of the list stands for. */
sealed interface PlaceKey {
    data class City(val id: Long) : PlaceKey
    data class Region(val key: String) : PlaceKey
    data class Country(val code: String) : PlaceKey

    /** Geotagged photos too far from any known city to have a place name. */
    data object Unknown : PlaceKey
}

/** The newest photo of a place, shown as its picture. */
class PlaceCover(val mediaId: Long, val modifiedAt: Long, val takenAt: Long)

/** One row of the Places list; countries hold regions, regions hold cities. */
class PlaceNode(val key: PlaceKey, val title: String, val photoCount: Int, val cover: PlaceCover, val children: List<PlaceNode>)

/**
 * Groups the per-city counts into the tree the Places list shows: country, region, city. Every level is sorted by how many
 * photos it holds, and a country's or region's picture is the newest photo of anything inside it.
 */
object PlaceTree {
    fun build(rows: List<PlaceGroupRow>, names: PlaceNames): List<PlaceNode> {
        val known = rows.filter { it.countryCode != null && it.cityId != null }
        val countries = known.groupBy { it.countryCode!! }.map { (code, inCountry) -> country(code, inCountry, names) }
        val unknown = rows.filter { it.countryCode == null || it.cityId == null }
        return countries.sortedByDescending { it.photoCount } + listOfNotNull(unknownNode(unknown))
    }

    private fun country(code: String, rows: List<PlaceGroupRow>, names: PlaceNames): PlaceNode {
        val regions = rows.groupBy { it.regionKey.orEmpty() }.map { (key, inRegion) -> region(key, inRegion, names) }
        return node(PlaceKey.Country(code), names.country(code), rows, regions.sortedByDescending { it.photoCount })
    }

    private fun region(key: String, rows: List<PlaceGroupRow>, names: PlaceNames): PlaceNode {
        val cities = rows.map { row -> node(PlaceKey.City(row.cityId!!), names.city(row.cityId) ?: UNNAMED, listOf(row), emptyList()) }
        return node(PlaceKey.Region(key), names.region(key) ?: cities.first().title, rows, cities.sortedByDescending { it.photoCount })
    }

    private fun unknownNode(rows: List<PlaceGroupRow>): PlaceNode? =
        if (rows.isEmpty()) null else node(PlaceKey.Unknown, UNNAMED, rows, emptyList())

    private fun node(key: PlaceKey, title: String, rows: List<PlaceGroupRow>, children: List<PlaceNode>): PlaceNode {
        val newest = rows.maxBy { it.coverTakenAt }
        return PlaceNode(key, title, rows.sumOf { it.photoCount }, PlaceCover(newest.coverMediaId, newest.coverModifiedAt, newest.coverTakenAt), children)
    }

    /** Replaced by a localized string on screen; only ever shown for data missing from the place files. */
    const val UNNAMED = ""
}
