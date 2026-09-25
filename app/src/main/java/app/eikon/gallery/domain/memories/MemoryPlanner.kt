package app.eikon.gallery.domain.memories

import app.eikon.gallery.domain.places.Trip
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

/** A trip found by the trip rules, with where it went in words (null if unknown). */
class NamedTrip(val trip: Trip, val name: String?)

/** A named person's photo count in one year. */
class PersonYear(val personId: Long, val isFavorite: Boolean, val year: Int, val count: Int)

/** What the planner knows about the library. Nothing else is looked at. */
class MemoryInputs(
    /** Photos and videos per day, on the user's calendar. */
    val dayCounts: Map<LocalDate, Int>,
    val trips: List<NamedTrip>,
    val people: List<PersonYear>,
)

/** The numbers behind the memory rules. Like the trip rules, they are the whole explanation of why something is offered. */
object MemoryPolicy {
    /** "On this day": a past year counts when that day has at least this many photos. */
    const val ON_THIS_DAY_MIN = 4
    const val ON_THIS_DAY_MAX_YEARS = 4

    /** "A year ago": the week around this date a year ago needs this many photos. */
    const val YEAR_AGO_MIN = 15

    /** A trip, weekend or day trip needs this many photos (the trip rules already ask for 10). */
    const val TRIP_MIN = 15

    /** Trips older than this are still offered, but far down. */
    const val RECENT_YEARS = 5

    /** A season needs this many photos, and a season still in progress this many days. */
    const val SEASON_MIN = 60
    const val SEASON_MIN_DAYS_IN = 60

    /** A named person in a year needs this many photos. */
    const val PERSON_YEAR_MIN = 15

    const val TOTAL_LIMIT = 12
    val PER_KIND_LIMIT = mapOf(
        MemoryKind.ON_THIS_DAY to 1, MemoryKind.YEAR_AGO to 1, MemoryKind.TRIP to 3, MemoryKind.WEEKEND to 2,
        MemoryKind.DAY_TRIP to 1, MemoryKind.SEASON to 2, MemoryKind.PERSON to 3,
    )
}

/**
 * Chooses which memories to offer today, with plain rules and no model: each rule looks at dates, trips or a person's photo
 * counts and proposes candidates with a score (more photos, more recent, an anniversary today: higher); the user's
 * choices remove some; then the best are taken, a few of each kind, never two about the same days.
 *
 * Not implemented: memories about pets (they need the content analysis at planning time) and "family moments" (there is no
 * reliable signal for it). Music is not part of memories.
 */
class MemoryPlanner(private val zone: ZoneId) {
    fun plan(today: LocalDate, inputs: MemoryInputs, preferences: MemoryPreferences = MemoryPreferences.NONE): List<Memory> {
        val counts = inputs.dayCounts.filterKeys { it !in preferences.excludedDays }
        val candidates = onThisDay(today, counts) + yearAgo(today, counts) + awayMemories(today, counts, inputs.trips) +
            seasons(today, counts) + people(today, inputs.people, preferences)
        return choose(candidates.filter { it.id.toArg() !in preferences.hidden }, preferences)
    }

    // --- Rules -------------------------------------------------------------------------------------

    private fun onThisDay(today: LocalDate, counts: Map<LocalDate, Int>): List<Memory> {
        val years = (today.year - 1 downTo today.year - MAX_LOOKBACK).filter { year ->
            val day = dateOrNull(year, today.monthValue, today.dayOfMonth)
            day != null && (counts[day] ?: 0) >= MemoryPolicy.ON_THIS_DAY_MIN
        }.take(MemoryPolicy.ON_THIS_DAY_MAX_YEARS)
        if (years.isEmpty()) return emptyList()
        val total = years.sumOf { counts.getValue(LocalDate.of(it, today.monthValue, today.dayOfMonth)) }
        return listOf(Memory(MemoryId.OnThisDay(today.monthValue, today.dayOfMonth, years), total, ON_THIS_DAY_SCORE + years.size * 4))
    }

    private fun yearAgo(today: LocalDate, counts: Map<LocalDate, Int>): List<Memory> {
        val center = dateOrNull(today.year - 1, today.monthValue, today.dayOfMonth) ?: return emptyList()
        val total = count(counts, center.minusDays(MemoryId.YEAR_AGO_DAYS_BEFORE), center.plusDays(MemoryId.YEAR_AGO_DAYS_AFTER))
        if (total < MemoryPolicy.YEAR_AGO_MIN) return emptyList()
        return listOf(Memory(MemoryId.YearAgo(center), total, YEAR_AGO_SCORE + minOf(total, 100) / 10.0))
    }

    /** Trips found by the trip rules, told apart by how long they were: a day trip, a weekend away, a real trip. */
    private fun awayMemories(today: LocalDate, counts: Map<LocalDate, Int>, trips: List<NamedTrip>): List<Memory> = trips.mapNotNull { named ->
        val trip = named.trip
        val total = count(counts, trip.firstDay, trip.lastDay.plusDays(1))
        if (total < MemoryPolicy.TRIP_MIN) return@mapNotNull null
        val kind = kindOf(trip)
        val id = MemoryId.Away(kind, trip.startMillis, trip.endMillis, named.name)
        Memory(id, total, awayScore(today, trip.firstDay, total))
    }

    private fun kindOf(trip: Trip): MemoryKind = when {
        trip.days == 1 -> MemoryKind.DAY_TRIP
        trip.days <= 3 && coversWeekend(trip) -> MemoryKind.WEEKEND
        else -> MemoryKind.TRIP
    }

    private fun coversWeekend(trip: Trip): Boolean =
        (0 until trip.days).any { trip.firstDay.plusDays(it.toLong()).dayOfWeek == DayOfWeek.SATURDAY } &&
            (0 until trip.days).any { trip.firstDay.plusDays(it.toLong()).dayOfWeek == DayOfWeek.SUNDAY }

    /** Recent trips first, size helps, and a trip whose anniversary is within a week of today rises above the rest. */
    private fun awayScore(today: LocalDate, first: LocalDate, total: Int): Double {
        val ageYears = (today.toEpochDay() - first.toEpochDay()) / DAYS_PER_YEAR
        val recency = if (ageYears <= MemoryPolicy.RECENT_YEARS) 40.0 - ageYears * 6 else 5.0
        val anniversary = if (anniversaryDistance(today, first) <= ANNIVERSARY_DAYS && first.year < today.year) ANNIVERSARY_BONUS else 0.0
        return recency + minOf(total, 200) / 10.0 + anniversary
    }

    private fun seasons(today: LocalDate, counts: Map<LocalDate, Int>): List<Memory> {
        val years = (today.year downTo today.year - MemoryPolicy.RECENT_YEARS)
        return years.flatMap { year -> MemorySeason.entries.map { it to year } }.mapNotNull { (season, year) ->
            val (from, to) = season.range(year)
            val elapsed = minOf(to.toEpochDay(), today.toEpochDay() + 1) - from.toEpochDay()
            if (from > today || elapsed < MemoryPolicy.SEASON_MIN_DAYS_IN) return@mapNotNull null
            val total = count(counts, from, to)
            if (total < MemoryPolicy.SEASON_MIN) return@mapNotNull null
            val monthsSinceEnd = max(0.0, (today.toEpochDay() - to.toEpochDay()) / DAYS_PER_MONTH)
            Memory(MemoryId.SeasonOf(season, year), total, SEASON_SCORE - monthsSinceEnd * 1.5 + minOf(total, 300) / 30.0)
        }
    }

    private fun people(today: LocalDate, people: List<PersonYear>, preferences: MemoryPreferences): List<Memory> =
        people.filter { it.count >= MemoryPolicy.PERSON_YEAR_MIN && it.personId !in preferences.lessOf && it.year <= today.year }.map { p ->
            val recency = max(0, MemoryPolicy.RECENT_YEARS - (today.year - p.year)) * 4.0
            val favorite = if (p.isFavorite) FAVORITE_BONUS else 0.0
            Memory(MemoryId.WithPerson(p.personId, p.year), p.count, PERSON_SCORE + recency + favorite + minOf(p.count, 200) / 20.0)
        }

    // --- Choosing --------------------------------------------------------------------------------------

    private fun choose(candidates: List<Memory>, preferences: MemoryPreferences): List<Memory> {
        val chosen = ArrayList<Memory>()
        val perKind = HashMap<MemoryKind, Int>()
        for (memory in candidates.sortedByDescending { it.score }) {
            val kind = memory.id.kind
            val limit = (MemoryPolicy.PER_KIND_LIMIT.getValue(kind) - (preferences.fewer[kind] ?: 0)).coerceAtLeast(0)
            if ((perKind[kind] ?: 0) >= limit || chosen.any { overlaps(it, memory) }) continue
            chosen += memory
            perKind[kind] = (perKind[kind] ?: 0) + 1
            if (chosen.size == MemoryPolicy.TOTAL_LIMIT) break
        }
        return chosen
    }

    /** Two memories about the same days are one story told twice; the better one wins. A person's year never counts as the same days. */
    private fun overlaps(a: Memory, b: Memory): Boolean {
        if (a.id.kind == MemoryKind.PERSON || b.id.kind == MemoryKind.PERSON) return a.id == b.id
        val pa = a.id.periods(zone)
        val pb = b.id.periods(zone)
        return pa.any { x -> pb.any { y -> x.startMillis < y.endMillis && y.startMillis < x.endMillis } }
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private fun count(counts: Map<LocalDate, Int>, from: LocalDate, toExclusive: LocalDate): Int =
        counts.entries.sumOf { (day, n) -> if (day >= from && day < toExclusive) n else 0 }

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? = runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /** Days between the same calendar date in different years, ignoring the year. */
    private fun anniversaryDistance(today: LocalDate, past: LocalDate): Long {
        val thisYears = dateOrNull(today.year, past.monthValue, past.dayOfMonth) ?: return Long.MAX_VALUE
        return kotlin.math.abs(today.toEpochDay() - thisYears.toEpochDay())
    }

    private companion object {
        const val MAX_LOOKBACK = 25
        const val DAYS_PER_YEAR = 365.25
        const val DAYS_PER_MONTH = 30.4
        const val ANNIVERSARY_DAYS = 7L
        const val ANNIVERSARY_BONUS = 25.0
        const val FAVORITE_BONUS = 10.0
        const val ON_THIS_DAY_SCORE = 60.0
        const val YEAR_AGO_SCORE = 45.0
        const val SEASON_SCORE = 30.0
        const val PERSON_SCORE = 20.0
    }
}
