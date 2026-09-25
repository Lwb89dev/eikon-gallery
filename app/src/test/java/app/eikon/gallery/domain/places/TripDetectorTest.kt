package app.eikon.gallery.domain.places

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripDetectorTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val detector = TripDetector(zone)
    private var nextId = 1L

    private val rome = Triple(41.9, 12.5, 100L)
    private val naples = Triple(40.85, 14.27, 101L)
    private val palermo = Triple(38.12, 13.36, 200L)
    private val catania = Triple(37.5, 15.09, 201L)
    private val paris = Triple(48.85, 2.35, 300L)
    private val madrid = Triple(40.4, -3.7, 400L)

    private fun shot(date: LocalDate, place: Triple<Double, Double, Long>, region: String, country: String, hour: Int = 12) = GeoShot(
        nextId++, date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(), place.first, place.second, place.third, region, country,
    )

    /** [count] photos a day from [start] for [days] days, all at [place]. */
    private fun stay(start: LocalDate, days: Int, count: Int, place: Triple<Double, Double, Long>, region: String, country: String = "IT") =
        (0 until days).flatMap { d -> (0 until count).map { shot(start.plusDays(d.toLong()), place, region, country, hour = 8 + it % 12) } }

    /** A year of ordinary life: a few photos a week in Rome. */
    private fun homeLife(from: LocalDate, days: Int) =
        (0 until days step 2).flatMap { d -> stay(from.plusDays(d.toLong()), 1, 3, rome, "IT.07") }

    private val base = LocalDate.of(2026, 1, 1)

    /** Ordinary life at home plus the given stays elsewhere; on the days of a stay nothing is shot at home. */
    private fun withHome(vararg stays: List<GeoShot>): List<GeoShot> {
        val away = stays.flatMap { it }
        val awayDays = away.map { java.time.Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate() }.toSet()
        val home = homeLife(base, 300).filter { java.time.Instant.ofEpochMilli(it.takenAt).atZone(zone).toLocalDate() !in awayDays }
        return home + away
    }

    @Test
    fun aWeekInSicilyFromHomeInRomeIsATripNamedAfterItsRegion() {
        val shots = withHome(stay(LocalDate.of(2026, 8, 12), 7, 8, palermo, "IT.15"))

        val trip = detector.detect(shots).single()

        assertEquals(LocalDate.of(2026, 8, 12), trip.firstDay)
        assertEquals(LocalDate.of(2026, 8, 18), trip.lastDay)
        assertEquals(7, trip.days)
        assertEquals(56, trip.photoCount)
        assertEquals(TripPlace.Region("IT.15"), trip.place)
        assertTrue("about 420 km from Rome: ${trip.distanceKm}", trip.distanceKm in 400..440)
        assertEquals(LocalDate.of(2026, 8, 12).atStartOfDay(zone).toInstant().toEpochMilli(), trip.startMillis)
        assertEquals(LocalDate.of(2026, 8, 19).atStartOfDay(zone).toInstant().toEpochMilli(), trip.endMillis)
    }

    @Test
    fun aTripTouchingTwoRegionsOfOneCountryIsNamedAfterTheBusiestCityOrTheCountry() {
        val shots = withHome(stay(LocalDate.of(2026, 8, 12), 3, 10, palermo, "IT.15"), stay(LocalDate.of(2026, 8, 15), 3, 10, naples, "IT.04"))
        // Naples is ~190 km from Rome (away); Palermo is farther. Half and half, so no region or city holds enough.
        val trip = detector.detect(shots).single()
        assertEquals(TripPlace.Country(listOf("IT")), trip.place)
    }

    @Test
    fun aTripAbroadTwoCountriesIsNamedByBoth() {
        val shots = withHome(stay(LocalDate.of(2026, 5, 1), 3, 10, paris, "FR.11", "FR"), stay(LocalDate.of(2026, 5, 4), 3, 10, madrid, "ES.29", "ES"))
        assertEquals(TripPlace.Country(listOf("FR", "ES")), detector.detect(shots).single().place)
    }

    @Test
    fun aWeekendInACityWithinTheAwayDistanceIsATripButAWeekendNearHomeIsNot() {
        val far = withHome(stay(LocalDate.of(2026, 3, 14), 2, 10, paris, "FR.11", "FR"))
        assertEquals(1, detector.detect(far).size)

        val latina = Triple(41.47, 12.9, 500L) // about 75 km from Rome
        val near = withHome(stay(LocalDate.of(2026, 3, 14), 2, 10, latina, "IT.07"))
        assertTrue(detector.detect(near).isEmpty())
    }

    @Test
    fun tooFewPhotosOrASingleQuietDayIsNotATrip() {
        assertTrue(detector.detect(withHome(stay(LocalDate.of(2026, 3, 14), 3, 3, paris, "FR.11", "FR"))).isEmpty()) // 9 photos
        assertTrue(detector.detect(withHome(stay(LocalDate.of(2026, 3, 14), 1, 12, paris, "FR.11", "FR"))).isEmpty()) // one day, 12 photos
        assertEquals(1, detector.detect(withHome(stay(LocalDate.of(2026, 3, 14), 1, 30, paris, "FR.11", "FR"))).size) // a busy day trip
    }

    @Test
    fun aDayWithoutPhotosInsideATripDoesNotSplitItButTwoDoes() {
        val gap1 = withHome(stay(LocalDate.of(2026, 8, 1), 3, 6, palermo, "IT.15"), stay(LocalDate.of(2026, 8, 5), 3, 6, catania, "IT.15"))
        assertEquals(1, detector.detect(gap1).size)

        val gap3 = withHome(stay(LocalDate.of(2026, 8, 1), 3, 6, palermo, "IT.15"), stay(LocalDate.of(2026, 8, 7), 3, 6, catania, "IT.15"))
        assertEquals(2, detector.detect(gap3).size)
    }

    @Test
    fun goingHomeBetweenTwoStaysMakesTwoTrips() {
        val shots = withHome(stay(LocalDate.of(2026, 8, 1), 3, 6, palermo, "IT.15"), stay(LocalDate.of(2026, 8, 4), 1, 6, rome, "IT.07"), stay(LocalDate.of(2026, 8, 5), 3, 6, catania, "IT.15"))
        assertEquals(2, detector.detect(shots).size)
    }

    @Test
    fun tripsAreListedNewestFirst() {
        val shots = withHome(stay(LocalDate.of(2026, 2, 1), 3, 6, paris, "FR.11", "FR"), stay(LocalDate.of(2026, 9, 1), 3, 6, madrid, "ES.29", "ES"))
        val trips = detector.detect(shots)
        assertEquals(listOf(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 2, 1)), trips.map { it.firstDay })
    }

    @Test
    fun whenTheUserMovesTheNewCityBecomesHomeAndOldTripsToItAreNotTrips() {
        // Two years in Rome, then two years in Paris. Weekends in Paris while living in Rome are trips; living there is not.
        val rome = homeLife(LocalDate.of(2023, 1, 1), 700)
        val trip = stay(LocalDate.of(2024, 6, 1), 3, 8, paris, "FR.11", "FR")
        val paris = (0 until 700 step 2).flatMap { d -> stay(LocalDate.of(2025, 3, 1).plusDays(d.toLong()), 1, 3, paris, "FR.11", "FR") }
        val trips = detector.detect(rome + trip + paris)
        assertEquals(listOf(LocalDate.of(2024, 6, 1)), trips.map { it.firstDay })
    }

    @Test
    fun noPositionsMeansNoTrips() {
        assertTrue(detector.detect(emptyList()).isEmpty())
    }

    @Test
    fun aDayIsTheDayOnTheUsersClockNotUtc() {
        // 23:30 on 1 August in Rome is 21:30 UTC the same day; 00:30 on 2 August local is 22:30 UTC on 1 August.
        val late = homeLife(base, 300) + (0 until 12).map { GeoShot(nextId++, LocalDate.of(2026, 8, 1).atTime(23, 30).atZone(zone).toInstant().toEpochMilli() + it * 1000L, paris.first, paris.second, paris.third, "FR.11", "FR") } +
            (0 until 12).map { GeoShot(nextId++, LocalDate.of(2026, 8, 2).atTime(0, 30).atZone(zone).toInstant().toEpochMilli() + it * 1000L, paris.first, paris.second, paris.third, "FR.11", "FR") }
        val trip = detector.detect(late).single()
        assertEquals(LocalDate.of(2026, 8, 1), trip.firstDay)
        assertEquals(LocalDate.of(2026, 8, 2), trip.lastDay)
    }

    @Test
    fun theDistanceHelperKnowsRomeToParis() {
        assertEquals(1_105.0, distanceKm(41.9, 12.5, 48.85, 2.35), 25.0)
        assertEquals(0.0, distanceKm(10.0, 10.0, 10.0, 10.0), 1e-9)
    }
}
