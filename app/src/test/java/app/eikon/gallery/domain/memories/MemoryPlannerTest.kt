package app.eikon.gallery.domain.memories

import app.eikon.gallery.domain.places.Trip
import app.eikon.gallery.domain.places.TripPlace
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPlannerTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val planner = MemoryPlanner(zone)
    private val today = LocalDate.of(2026, 9, 25)

    private fun counts(vararg entries: Pair<LocalDate, Int>) = mapOf(*entries)

    private fun days(from: LocalDate, count: Int, perDay: Int): Map<LocalDate, Int> = (0 until count).associate { from.plusDays(it.toLong()) to perDay }

    private fun trip(first: LocalDate, last: LocalDate, photos: Int = 30) = Trip(
        startMillis = first.atStartOfDay(zone).toInstant().toEpochMilli(),
        endMillis = last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        firstDay = first, lastDay = last, photoCount = photos, place = TripPlace.Unknown, distanceKm = 300, homeLatitude = 41.9, homeLongitude = 12.5,
    )

    private fun plan(inputs: MemoryInputs, preferences: MemoryPreferences = MemoryPreferences.NONE) = planner.plan(today, inputs, preferences)

    private fun kinds(memories: List<Memory>) = memories.map { it.id.kind }

    // --- on this day ------------------------------------------------------------------------------

    @Test
    fun onThisDayListsThePastYearsWithEnoughPhotosOnTodaysDate() {
        val library = counts(LocalDate.of(2024, 9, 25) to 9, LocalDate.of(2022, 9, 25) to 5, LocalDate.of(2023, 9, 25) to 2, today to 20)

        val memory = plan(MemoryInputs(library, emptyList(), emptyList())).single { it.id.kind == MemoryKind.ON_THIS_DAY }

        assertEquals(MemoryId.OnThisDay(9, 25, listOf(2024, 2022)), memory.id) // 2023 had only two photos; this year is not a memory
        assertEquals(14, memory.itemCount)
    }

    @Test
    fun aLeapDayOnlyLooksAtLeapYears() {
        val leapToday = LocalDate.of(2028, 2, 29)
        val library = counts(LocalDate.of(2024, 2, 29) to 8, LocalDate.of(2027, 2, 28) to 8)
        val memory = planner.plan(leapToday, MemoryInputs(library, emptyList(), emptyList())).single { it.id.kind == MemoryKind.ON_THIS_DAY }
        assertEquals(MemoryId.OnThisDay(2, 29, listOf(2024)), memory.id)
    }

    // --- a year ago ---------------------------------------------------------------------------------

    @Test
    fun aYearAgoNeedsAWeekOfPhotosAroundThisDateLastYear() {
        val busy = days(LocalDate.of(2025, 9, 22), 7, 3) // 21 photos
        assertTrue(plan(MemoryInputs(busy, emptyList(), emptyList())).any { it.id.kind == MemoryKind.YEAR_AGO })

        val quiet = days(LocalDate.of(2025, 9, 22), 7, 1) // 7 photos
        assertFalse(plan(MemoryInputs(quiet, emptyList(), emptyList())).any { it.id.kind == MemoryKind.YEAR_AGO })
    }

    // --- trips ----------------------------------------------------------------------------------------

    @Test
    fun tripsAreToldApartByLength() {
        val day = trip(LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 2), 30)          // Saturday alone
        val weekend = trip(LocalDate.of(2026, 3, 14), LocalDate.of(2026, 3, 15), 30)   // Saturday and Sunday
        val midweek = trip(LocalDate.of(2026, 4, 7), LocalDate.of(2026, 4, 8), 30)     // Tuesday and Wednesday
        val week = trip(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 18), 60)
        val library = listOf(day, weekend, midweek, week).fold(emptyMap<LocalDate, Int>()) { all, t ->
            all + (0 until t.days).associate { t.firstDay.plusDays(it.toLong()) to t.photoCount / t.days }
        }

        val memories = plan(MemoryInputs(library, listOf(day, weekend, midweek, week).map { NamedTrip(it, "Somewhere") }, emptyList()))
        val byKind = memories.associate { (it.id as? MemoryId.Away)?.startMillis to it.id.kind }

        assertEquals(MemoryKind.DAY_TRIP, byKind[day.startMillis])
        assertEquals(MemoryKind.WEEKEND, byKind[weekend.startMillis])
        assertEquals(MemoryKind.TRIP, byKind[midweek.startMillis])
        assertEquals(MemoryKind.TRIP, byKind[week.startMillis])
    }

    @Test
    fun aTripWithFewPhotosOnTheUsersCalendarIsNotOffered() {
        val t = trip(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 14), photos = 12)
        val library = days(t.firstDay, 3, 4) // 12 photos in total: below the memory minimum of 15
        assertTrue(plan(MemoryInputs(library, listOf(NamedTrip(t, "Sicily")), emptyList())).none { it.id is MemoryId.Away })
    }

    @Test
    fun aTripWhoseAnniversaryIsThisWeekComesFirst() {
        val nearAnniversary = trip(LocalDate.of(2024, 9, 18), LocalDate.of(2024, 9, 24), 60) // began 7 days before today's date, not covering it
        val recent = trip(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 7), 60)
        val library = days(nearAnniversary.firstDay, 7, 9) + days(recent.firstDay, 7, 9)

        val memories = plan(MemoryInputs(library, listOf(NamedTrip(nearAnniversary, "A"), NamedTrip(recent, "B")), emptyList()))

        assertEquals("A", (memories.first { it.id is MemoryId.Away }.id as MemoryId.Away).label)
    }

    // --- seasons ---------------------------------------------------------------------------------------

    @Test
    fun aFinishedSeasonWithEnoughPhotosIsOfferedAndTheOneJustStartingIsNot() {
        val summer = days(LocalDate.of(2026, 6, 1), 92, 2) // 184 photos
        val autumnStart = days(LocalDate.of(2026, 9, 1), 25, 3) // autumn began 25 days ago: too early
        val memories = plan(MemoryInputs(summer + autumnStart, emptyList(), emptyList()))

        val seasons = memories.mapNotNull { it.id as? MemoryId.SeasonOf }
        assertEquals(listOf(MemoryId.SeasonOf(MemorySeason.SUMMER, 2026)), seasons)
    }

    @Test
    fun winterBelongsToTheYearInWhichItEnds() {
        assertEquals(LocalDate.of(2025, 12, 1) to LocalDate.of(2026, 3, 1), MemorySeason.WINTER.range(2026))
    }

    // --- people -----------------------------------------------------------------------------------------

    @Test
    fun aPersonNeedsEnoughPhotosInAYearAndFavoritesRankHigher() {
        val people = listOf(PersonYear(1, false, 2025, 30), PersonYear(2, true, 2025, 30), PersonYear(3, false, 2025, 10))
        val memories = plan(MemoryInputs(emptyMap(), emptyList(), people))
        assertEquals(listOf(2L, 1L), memories.map { (it.id as MemoryId.WithPerson).person })
    }

    @Test
    fun showingLessOfAPersonDropsTheirMemories() {
        val people = listOf(PersonYear(1, false, 2025, 30), PersonYear(2, false, 2025, 30))
        val memories = plan(MemoryInputs(emptyMap(), emptyList(), people), MemoryPreferences(lessOf = setOf(1L)))
        assertEquals(listOf(2L), memories.map { (it.id as MemoryId.WithPerson).person })
    }

    // --- the user's choices ------------------------------------------------------------------------------

    @Test
    fun aHiddenMemoryStaysHidden() {
        val library = counts(LocalDate.of(2024, 9, 25) to 9)
        val id = plan(MemoryInputs(library, emptyList(), emptyList())).single().id
        assertTrue(plan(MemoryInputs(library, emptyList(), emptyList()), MemoryPreferences(hidden = setOf(id.toArg()))).isEmpty())
    }

    @Test
    fun showFewerOfAKindNarrowsItsShareUntilItIsGone() {
        val trips = (0 until 4).map { trip(LocalDate.of(2026, 1 + it, 10), LocalDate.of(2026, 1 + it, 16), 40) }
        val library = trips.fold(emptyMap<LocalDate, Int>()) { all, t -> all + days(t.firstDay, 7, 6) }
        val inputs = MemoryInputs(library, trips.map { NamedTrip(it, null) }, emptyList())

        assertEquals(3, plan(inputs).count { it.id.kind == MemoryKind.TRIP })
        assertEquals(2, plan(inputs, MemoryPreferences(fewer = mapOf(MemoryKind.TRIP to 1))).count { it.id.kind == MemoryKind.TRIP })
        assertEquals(0, plan(inputs, MemoryPreferences(fewer = mapOf(MemoryKind.TRIP to 3))).count { it.id.kind == MemoryKind.TRIP })
    }

    @Test
    fun anExcludedDayIsLeftOutSoAMemoryThatNeededItDisappears() {
        val library = counts(LocalDate.of(2024, 9, 25) to 9)
        assertTrue(plan(MemoryInputs(library, emptyList(), emptyList()), MemoryPreferences(excludedDays = setOf(LocalDate.of(2024, 9, 25)))).isEmpty())
    }

    @Test
    fun twoMemoriesAboutTheSameDaysAreOneStoryTheBetterOneWins() {
        // A week-long trip a year ago also makes the "a year ago" week; only one of them is offered.
        val t = trip(LocalDate.of(2025, 9, 22), LocalDate.of(2025, 9, 28), 60)
        val library = days(t.firstDay, 7, 9)
        val memories = plan(MemoryInputs(library, listOf(NamedTrip(t, "Rome")), emptyList()))
        assertEquals(1, memories.count { it.id.kind == MemoryKind.TRIP || it.id.kind == MemoryKind.YEAR_AGO })
    }

    @Test
    fun theListIsCappedAndAnEmptyLibraryHasNoMemories() {
        assertTrue(plan(MemoryInputs(emptyMap(), emptyList(), emptyList())).isEmpty())
        val people = (1L..40L).map { PersonYear(it, false, 2025, 30) }
        assertEquals(MemoryPolicy.PER_KIND_LIMIT.getValue(MemoryKind.PERSON), plan(MemoryInputs(emptyMap(), emptyList(), people)).size)
    }

    // --- ids ---------------------------------------------------------------------------------------------

    @Test
    fun everyIdRoundTripsThroughItsArgument() {
        val ids = listOf(
            MemoryId.OnThisDay(9, 25, listOf(2024, 2022)),
            MemoryId.YearAgo(LocalDate.of(2025, 9, 25)),
            MemoryId.Away(MemoryKind.TRIP, 1_000, 2_000, "Sicilia: Palermo"),
            MemoryId.Away(MemoryKind.WEEKEND, 1_000, 2_000, null),
            MemoryId.Away(MemoryKind.DAY_TRIP, 1_000, 2_000, "Roma"),
            MemoryId.SeasonOf(MemorySeason.SUMMER, 2026),
            MemoryId.WithPerson(7, 2025),
        )
        ids.forEach { assertEquals(it, MemoryId.parse(it.toArg())) }
    }

    @Test
    fun malformedArgumentsAreRejected() {
        listOf("", "otd:9:25", "otd:x:y:z", "trip:1", "season:winter:x", "person:1", "nope:1:2:3", "yago:not-a-date").forEach { assertNull("for $it", MemoryId.parse(it)) }
    }

    @Test
    fun periodsCoverTheRightDays() {
        val range = MemoryId.WithPerson(1, 2025).periods(zone).single()
        assertEquals(LocalDate.of(2025, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(), range.startMillis)
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli(), range.endMillis)
        assertEquals(2, MemoryId.OnThisDay(9, 25, listOf(2024, 2022)).periods(zone).size)
        assertEquals(1, MemoryId.OnThisDay(2, 29, listOf(2023, 2024)).periods(zone).size) // 29 February 2023 does not exist
    }
}
