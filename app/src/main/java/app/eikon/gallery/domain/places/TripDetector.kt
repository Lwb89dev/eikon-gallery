package app.eikon.gallery.domain.places

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** One geotagged photo, with the place the analysis resolved for it (any of the three ids may be missing). */
class GeoShot(
    val mediaId: Long,
    val takenAt: Long,
    val latitude: Double,
    val longitude: Double,
    val cityId: Long?,
    val regionKey: String?,
    val countryCode: String?,
)

/** Where a trip went, in the terms Places uses; naming it is left to whoever knows the place names. */
sealed interface TripPlace {
    data class City(val id: Long) : TripPlace
    data class Region(val key: String) : TripPlace
    data class Country(val codes: List<String>) : TripPlace
    data object Unknown : TripPlace
}

/**
 * A stretch of days spent far from home. [endMillis] is the start of the day after the last one, so the trip is the
 * half-open period `[startMillis, endMillis)` and includes photos taken on those days that have no position.
 */
data class Trip(
    val startMillis: Long,
    val endMillis: Long,
    val firstDay: LocalDate,
    val lastDay: LocalDate,
    /** Geotagged photos taken during it. */
    val photoCount: Int,
    val place: TripPlace,
    /** How far from home the trip's days were, at the median, in kilometres. */
    val distanceKm: Int,
    val homeLatitude: Double,
    val homeLongitude: Double,
) {
    val days: Int get() = (lastDay.toEpochDay() - firstDay.toEpochDay() + 1).toInt()
}

/** The numbers behind the trip rules. They are the whole explanation of why something is or is not a trip. */
object TripPolicy {
    /** A day is "away" when its photos were taken at least this far from where most photos are taken. */
    const val AWAY_KM = 100.0

    /** Home is the busiest place in the year around a day (in days with photos), so it follows the user when they move. */
    const val HOME_WINDOW_DAYS = 182

    /** Home is worked out on a grid of this many degrees (about 55 km): one city and its surroundings. */
    const val HOME_CELL_DEGREES = 0.5

    /** Days without photos allowed inside a trip before it is split in two. */
    const val MAX_GAP_DAYS = 2

    /** A trip needs this many geotagged photos... */
    const val MIN_PHOTOS = 10

    /** ...and either two days, or, for a single day, this many photos. */
    const val MIN_DAY_TRIP_PHOTOS = 25
}

/**
 * Finds trips with rules anyone can check, no model involved:
 *
 *  1. Each day with photos gets one position, the middle (median) of that day's photos.
 *  2. "Home" for a day is the busiest place in the half year either side of it, counted in days with photos, on a grid of
 *     [TripPolicy.HOME_CELL_DEGREES]; its position is the average of those days there.
 *  3. A day is *away* when its position is at least [TripPolicy.AWAY_KM] from home.
 *  4. A trip is a run of away days with no home day in between and no more than [TripPolicy.MAX_GAP_DAYS] days without
 *     photos inside it, with at least [TripPolicy.MIN_PHOTOS] photos and two days (or one day with
 *     [TripPolicy.MIN_DAY_TRIP_PHOTOS] photos).
 *
 * It only knows about geotagged photos, so it needs the Places analysis; without positions it finds nothing.
 */
class TripDetector(private val zone: ZoneId) {
    private class Day(val date: LocalDate, val latitude: Double, val longitude: Double, val shots: List<GeoShot>) {
        var home: Pair<Double, Double> = latitude to longitude
        var away = false
        var distance = 0.0
    }

    /** Trips in [shots] (any order), newest first. */
    fun detect(shots: List<GeoShot>): List<Trip> {
        val days = shots.groupBy { LocalDate.ofEpochDay(Math.floorDiv(it.takenAt + offsetMillis(it.takenAt), MILLIS_PER_DAY)) }
            .map { (date, day) -> day(date, day) }
            .sortedBy { it.date }
        markAway(days)
        return runs(days).mapNotNull { trip(it) }.sortedByDescending { it.startMillis }
    }

    private fun offsetMillis(takenAt: Long): Long = zone.rules.getOffset(java.time.Instant.ofEpochMilli(takenAt)).totalSeconds * 1000L

    private fun day(date: LocalDate, shots: List<GeoShot>) =
        Day(date, median(shots.map { it.latitude }), median(shots.map { it.longitude }), shots)

    private fun median(values: List<Double>): Double = values.sorted().let { it[it.size / 2] }

    // --- Home and away ------------------------------------------------------------------------------

    private fun markAway(days: List<Day>) {
        var from = 0
        var to = 0
        val counts = HashMap<Long, Int>()
        val sums = HashMap<Long, DoubleArray>()
        for (day in days) {
            while (to < days.size && days[to].date.toEpochDay() <= day.date.toEpochDay() + TripPolicy.HOME_WINDOW_DAYS) add(days[to++], counts, sums, +1)
            while (days[from].date.toEpochDay() < day.date.toEpochDay() - TripPolicy.HOME_WINDOW_DAYS) add(days[from++], counts, sums, -1)
            val busiest = counts.entries.filter { it.value > 0 }.maxWith(compareBy({ it.value }, { -it.key }))
            val total = sums.getValue(busiest.key)
            day.home = total[0] / busiest.value to total[1] / busiest.value
            day.distance = distanceKm(day.latitude, day.longitude, day.home.first, day.home.second)
            day.away = day.distance >= TripPolicy.AWAY_KM
        }
    }

    private fun add(day: Day, counts: MutableMap<Long, Int>, sums: MutableMap<Long, DoubleArray>, sign: Int) {
        val cell = cellOf(day.latitude, day.longitude)
        counts[cell] = (counts[cell] ?: 0) + sign
        val sum = sums.getOrPut(cell) { DoubleArray(2) }
        sum[0] += sign * day.latitude
        sum[1] += sign * day.longitude
    }

    private fun cellOf(latitude: Double, longitude: Double): Long =
        (floor(latitude / TripPolicy.HOME_CELL_DEGREES).toLong() shl CELL_SHIFT) xor (floor(longitude / TripPolicy.HOME_CELL_DEGREES).toLong() and CELL_MASK)

    // --- Trips ---------------------------------------------------------------------------------------

    /** Runs of consecutive away days: a home day, or too long a silence, ends a run. */
    private fun runs(days: List<Day>): List<List<Day>> {
        val runs = ArrayList<MutableList<Day>>()
        var current: MutableList<Day>? = null
        for (day in days) {
            val last = current?.last()
            val continues = day.away && last != null && day.date.toEpochDay() - last.date.toEpochDay() - 1 <= TripPolicy.MAX_GAP_DAYS
            when {
                continues -> current += day
                day.away -> current = mutableListOf(day).also { runs += it }
                else -> current = null
            }
        }
        return runs
    }

    private fun trip(run: List<Day>): Trip? {
        val shots = run.flatMap { it.shots }
        val days = run.last().date.toEpochDay() - run.first().date.toEpochDay() + 1
        val enough = shots.size >= TripPolicy.MIN_PHOTOS && (days >= 2 || shots.size >= TripPolicy.MIN_DAY_TRIP_PHOTOS)
        if (!enough) return null
        val home = run[run.size / 2].home
        return Trip(
            startMillis = run.first().date.atStartOfDay(zone).toInstant().toEpochMilli(),
            endMillis = run.last().date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            firstDay = run.first().date,
            lastDay = run.last().date,
            photoCount = shots.size,
            place = placeOf(shots),
            distanceKm = median(run.map { it.distance }).toInt(),
            homeLatitude = home.first,
            homeLongitude = home.second,
        )
    }

    /**
     * Where it went. One country holding at least 80% of the photos: its region if that holds 60% of them, else its
     * city if that holds more than half, else the country. Otherwise the two countries with the most photos.
     */
    private fun placeOf(shots: List<GeoShot>): TripPlace {
        val located = shots.filter { it.countryCode != null }
        if (located.isEmpty()) return TripPlace.Unknown
        val byCountry = located.groupingBy { it.countryCode!! }.eachCount().entries.sortedByDescending { it.value }
        val top = byCountry.first()
        if (top.value < located.size * COUNTRY_SHARE) return TripPlace.Country(byCountry.take(2).map { it.key })
        val inCountry = located.filter { it.countryCode == top.key }
        val region = inCountry.mapNotNull { it.regionKey }.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (region != null && region.value >= inCountry.size * REGION_SHARE) return TripPlace.Region(region.key)
        val city = inCountry.mapNotNull { it.cityId }.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (city != null && city.value > inCountry.size * CITY_SHARE) return TripPlace.City(city.key)
        return TripPlace.Country(listOf(top.key))
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
        const val CELL_SHIFT = 32
        const val CELL_MASK = 0xFFFFFFFFL
        const val COUNTRY_SHARE = 0.8
        const val REGION_SHARE = 0.6
        const val CITY_SHARE = 0.5
    }
}

/** Great-circle distance in kilometres. */
fun distanceKm(latitudeA: Double, longitudeA: Double, latitudeB: Double, longitudeB: Double): Double {
    val dLat = Math.toRadians(latitudeB - latitudeA)
    val dLon = Math.toRadians(longitudeB - longitudeA)
    val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(latitudeA)) * cos(Math.toRadians(latitudeB)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
}

private const val EARTH_RADIUS_KM = 6371.0
