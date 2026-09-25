package app.eikon.gallery.domain.memories

import app.eikon.gallery.domain.search.TimeRange
import java.time.LocalDate
import java.time.ZoneId

/** The kinds of memory eikon makes. Each has its own rule in [MemoryPlanner]. */
enum class MemoryKind { ON_THIS_DAY, YEAR_AGO, TRIP, WEEKEND, DAY_TRIP, SEASON, PERSON }

/** The meteorological seasons of the northern hemisphere, as the search uses them: winter is December to February. */
enum class MemorySeason(val firstMonth: Int) {
    WINTER(12), SPRING(3), SUMMER(6), AUTUMN(9),
    ;

    /** `[start, end)` of the [year]'s season; winter belongs to the year in which it ends (winter 2026 is Dec 2025 to Feb 2026). */
    fun range(year: Int): Pair<LocalDate, LocalDate> = when (this) {
        WINTER -> LocalDate.of(year - 1, 12, 1) to LocalDate.of(year, 3, 1)
        SPRING -> LocalDate.of(year, 3, 1) to LocalDate.of(year, 6, 1)
        SUMMER -> LocalDate.of(year, 6, 1) to LocalDate.of(year, 9, 1)
        AUTUMN -> LocalDate.of(year, 9, 1) to LocalDate.of(year, 12, 1)
    }
}

/**
 * Which memory this is, with everything needed to find its photos, so an id alone (it travels in a navigation route) says what to
 * show. Ids are stable: the same trip or day gives the same id, which is what lets "hide this memory" stick.
 */
sealed interface MemoryId {
    val kind: MemoryKind

    /** Every day in [ranges] of the memory, in the user's time zone. */
    fun periods(zone: ZoneId): List<TimeRange>

    /** The person whose photos this is about, if any. */
    val personId: Long? get() = null

    fun toArg(): String

    /** This day (month and day) in earlier [years]. */
    data class OnThisDay(val month: Int, val day: Int, val years: List<Int>) : MemoryId {
        override val kind get() = MemoryKind.ON_THIS_DAY
        override fun periods(zone: ZoneId) = years.mapNotNull { year -> runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { dayRange(it, zone) } }
        override fun toArg() = "otd:$month:$day:${years.joinToString(",")}"
    }

    /** The week around [center], which is today's date a year ago. */
    data class YearAgo(val center: LocalDate) : MemoryId {
        override val kind get() = MemoryKind.YEAR_AGO
        override fun periods(zone: ZoneId) = listOf(range(center.minusDays(YEAR_AGO_DAYS_BEFORE), center.plusDays(YEAR_AGO_DAYS_AFTER), zone))
        override fun toArg() = "yago:$center"
    }

    /** A trip, a weekend away or a day trip: a period found by the trip rules. [label] is where, if it is known. */
    data class Away(override val kind: MemoryKind, val startMillis: Long, val endMillis: Long, val label: String?) : MemoryId {
        override fun periods(zone: ZoneId) = listOf(TimeRange(startMillis, endMillis))
        override fun toArg() = "${kind.name.lowercase()}:$startMillis:$endMillis" + (label?.let { ":$it" } ?: "")
    }

    data class SeasonOf(val season: MemorySeason, val year: Int) : MemoryId {
        override val kind get() = MemoryKind.SEASON
        override fun periods(zone: ZoneId) = season.range(year).let { listOf(range(it.first, it.second, zone)) }
        override fun toArg() = "season:${season.name.lowercase()}:$year"
    }

    /** A named person in one [year]. */
    data class WithPerson(val person: Long, val year: Int) : MemoryId {
        override val kind get() = MemoryKind.PERSON
        override val personId get() = person
        override fun periods(zone: ZoneId) = listOf(range(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1), zone))
        override fun toArg() = "person:$person:$year"
    }

    companion object {
        const val YEAR_AGO_DAYS_BEFORE = 3L
        const val YEAR_AGO_DAYS_AFTER = 4L

        fun range(from: LocalDate, toExclusive: LocalDate, zone: ZoneId) =
            TimeRange(from.atStartOfDay(zone).toInstant().toEpochMilli(), toExclusive.atStartOfDay(zone).toInstant().toEpochMilli())

        fun dayRange(day: LocalDate, zone: ZoneId) = range(day, day.plusDays(1), zone)

        /** The id of an argument produced by [toArg], or null if it is malformed. */
        fun parse(arg: String): MemoryId? = runCatching {
            val parts = arg.split(':', limit = 4)
            when (parts[0]) {
                "otd" -> OnThisDay(parts[1].toInt(), parts[2].toInt(), parts[3].split(',').map { it.toInt() }).takeIf { it.years.isNotEmpty() }
                "yago" -> YearAgo(LocalDate.parse(parts[1] + (parts.getOrNull(2)?.let { ":$it" } ?: "")))
                "trip", "weekend", "day_trip" -> Away(MemoryKind.valueOf(parts[0].uppercase()), parts[1].toLong(), parts[2].toLong(), parts.getOrNull(3)?.takeIf { it.isNotEmpty() })
                "season" -> SeasonOf(MemorySeason.valueOf(parts[1].uppercase()), parts[2].toInt())
                "person" -> WithPerson(parts[1].toLong(), parts[2].toInt())
                else -> null
            }
        }.getOrNull()
    }
}

/**
 * What the user told Memories. [hidden] are memories to leave out; [fewer] counts how many times "show fewer like this" was
 * pressed for a kind; [lessOf] are people to show less of; [excludedDays] are days to leave out of every memory.
 */
data class MemoryPreferences(
    val hidden: Set<String> = emptySet(),
    val fewer: Map<MemoryKind, Int> = emptyMap(),
    val lessOf: Set<Long> = emptySet(),
    val excludedDays: Set<LocalDate> = emptySet(),
) {
    companion object {
        val NONE = MemoryPreferences()
    }
}

/** A memory to offer, with how many photos and videos it holds and how good a candidate it is. */
data class Memory(val id: MemoryId, val itemCount: Int, val score: Double)
