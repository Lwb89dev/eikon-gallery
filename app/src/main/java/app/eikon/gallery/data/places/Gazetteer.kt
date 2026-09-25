package app.eikon.gallery.data.places

import app.eikon.gallery.domain.search.PlaceMatch
import app.eikon.gallery.domain.search.PlaceMatcher
import app.eikon.gallery.domain.search.TextNormalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** A populated place from the bundled GeoNames extract. */
data class City(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val countryCode: String,
    /** GeoNames first-level region code within the country, e.g. "07"; may be blank. */
    val admin1: String,
    val population: Long,
) {
    /** `IT.07`: the key used to group cities into regions, matching the region table. */
    val regionKey: String get() = "$countryCode.$admin1"
}

/**
 * Offline place lookup over the bundled GeoNames data (cities above 15,000 inhabitants).
 *
 * It does two jobs entirely on the device: turn coordinates into the nearest known city (so photos
 * can be found by place without any coordinate ever leaving the phone), and turn a word the user typed
 * ("Roma", "Toscana", "Italia") into the places it names. Nothing here uses the network.
 */
class Gazetteer private constructor(
    private val cities: List<City>,
    private val cityNames: Map<String, List<Int>>,
    private val regionNames: Map<String, Set<String>>,
    private val countryNames: Map<String, Set<String>>,
    private val grid: Map<Int, IntArray>,
    val regionDisplayNames: Map<String, String>,
) : PlaceMatcher {
    val cityCount: Int get() = cities.size

    fun city(id: Long): City? = cities.firstOrNull { it.id == id }

    /**
     * Cities with at least [minPopulation] inhabitants inside the box, biggest first, at most [limit] of them: the
     * names drawn on the map for orientation.
     */
    fun citiesIn(minLatitude: Double, maxLatitude: Double, minLongitude: Double, maxLongitude: Double, minPopulation: Long, limit: Int): List<City> =
        cities.asSequence()
            .filter { it.population >= minPopulation && it.latitude in minLatitude..maxLatitude && it.longitude in minLongitude..maxLongitude }
            .sortedByDescending { it.population }
            .take(limit)
            .toList()

    /** The nearest city within [maxKm] of the point, or null in the middle of nowhere. */
    fun nearest(latitude: Double, longitude: Double, maxKm: Double = DEFAULT_MAX_KM): City? {
        for (radius in 1..MAX_CELL_RADIUS) {
            val best = bestInRing(latitude, longitude, radius) ?: continue
            return if (best.second <= maxKm) best.first else null
        }
        return null
    }

    private fun bestInRing(latitude: Double, longitude: Double, radius: Int): Pair<City, Double>? {
        val latCell = floor(latitude).toInt()
        val lonCell = floor(longitude).toInt()
        var best: Pair<City, Double>? = null
        for (dLat in -radius..radius) {
            for (dLon in -radius..radius) {
                val candidates = grid[cellKey(latCell + dLat, lonCell + dLon)] ?: continue
                for (index in candidates) {
                    val city = cities[index]
                    val distance = haversineKm(latitude, longitude, city.latitude, city.longitude)
                    if (best == null || distance < best.second) best = city to distance
                }
            }
        }
        return best
    }

    override fun match(normalizedName: String): PlaceMatch? {
        val cityIds = cityNames[normalizedName]?.take(MAX_CITIES_PER_NAME)?.map { cities[it].id }?.toSet().orEmpty()
        val countries = countryNames[normalizedName].orEmpty()
        val regions = regionNames[normalizedName].orEmpty()
        val match = PlaceMatch(cityIds, countries, regions)
        return if (match.isEmpty) null else match
    }

    companion object {
        const val DEFAULT_MAX_KM = 100.0
        private const val MAX_CELL_RADIUS = 3
        private const val MAX_CITIES_PER_NAME = 50
        private const val EARTH_RADIUS_KM = 6371.0

        /**
         * Builds the lookup from the asset files.
         * - [cityLines]: `id, name, lat, lon, countryCode, admin1, population, alt1|alt2|...` (tab separated)
         * - [regionLines]: `CC.admin1, name`
         * - [aliasLines]: `CC.admin1, alias` for regions known under another name (e.g. Toscana)
         * - [countryLocales]: languages whose country names are searchable (device language + English)
         */
        fun build(
            cityLines: Sequence<String>,
            regionLines: Sequence<String>,
            aliasLines: Sequence<String>,
            countryLocales: List<Locale>,
        ): Gazetteer {
            val cities = ArrayList<City>()
            val names = HashMap<String, MutableList<Int>>()
            for (line in cityLines) parseCity(line, cities, names)
            val regionNames = HashMap<String, MutableSet<String>>()
            val displayNames = HashMap<String, String>()
            regionLines.forEach { parseRegion(it, regionNames, displayNames) }
            aliasLines.forEach { parseAlias(it, regionNames) }
            return Gazetteer(cities, names, regionNames, countryNames(countryLocales), buildGrid(cities), displayNames)
        }

        private fun parseCity(line: String, cities: MutableList<City>, names: MutableMap<String, MutableList<Int>>) {
            val f = line.split('\t')
            if (f.size < CITY_FIELDS) return
            val city = City(
                id = f[0].toLongOrNull() ?: return,
                name = f[1],
                latitude = f[2].toDoubleOrNull() ?: return,
                longitude = f[3].toDoubleOrNull() ?: return,
                countryCode = f[4],
                admin1 = f[5],
                population = f[6].toLongOrNull() ?: 0,
            )
            val index = cities.size
            cities += city
            val allNames = listOf(f[1]) + f.getOrElse(ALTERNATES_FIELD) { "" }.split('|')
            allNames.map(TextNormalizer::name).filter { it.isNotEmpty() }.toSet().forEach {
                names.getOrPut(it) { mutableListOf() } += index
            }
        }

        private fun parseRegion(line: String, regions: MutableMap<String, MutableSet<String>>, display: MutableMap<String, String>) {
            val f = line.split('\t')
            if (f.size < 2) return
            display[f[0]] = f[1]
            regions.getOrPut(TextNormalizer.name(f[1])) { mutableSetOf() } += f[0]
        }

        private fun parseAlias(line: String, regions: MutableMap<String, MutableSet<String>>) {
            val f = line.split('\t')
            if (f.size < 2) return
            regions.getOrPut(TextNormalizer.name(f[1])) { mutableSetOf() } += f[0]
        }

        private fun countryNames(locales: List<Locale>): Map<String, Set<String>> {
            val result = HashMap<String, MutableSet<String>>()
            for (code in Locale.getISOCountries()) {
                for (locale in locales) {
                    val name = TextNormalizer.name(Locale.Builder().setRegion(code).build().getDisplayCountry(locale))
                    if (name.isNotEmpty()) result.getOrPut(name) { mutableSetOf() } += code
                }
            }
            return result
        }

        private fun buildGrid(cities: List<City>): Map<Int, IntArray> {
            val buckets = HashMap<Int, MutableList<Int>>()
            cities.forEachIndexed { index, city ->
                buckets.getOrPut(cellKey(floor(city.latitude).toInt(), floor(city.longitude).toInt())) { mutableListOf() } += index
            }
            return buckets.mapValues { it.value.toIntArray() }
        }

        private fun cellKey(latCell: Int, lonCell: Int): Int = (latCell + 90) * 400 + (lonCell + 180)

        private const val CITY_FIELDS = 7
        private const val ALTERNATES_FIELD = 7

        /** Great-circle distance between two points, in kilometres. */
        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_KM * asin(sqrt(max(0.0, a)))
        }
    }
}
