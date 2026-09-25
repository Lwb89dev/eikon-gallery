package app.eikon.gallery.domain.search

import androidx.compose.runtime.Immutable
import app.eikon.gallery.domain.LibraryFilters

/** Half-open time range `[startMillis, endMillis)` in epoch milliseconds. */
@Immutable
data class TimeRange(val startMillis: Long, val endMillis: Long)

/** A date condition extracted from a query. Several conditions in one query are OR-ed together. */
sealed interface DateSpec {
    /** An exact day, month, year, week... */
    data class Range(val range: TimeRange) : DateSpec

    /** Any year, one of these months (1..12): "agosto", "estate". */
    data class Months(val months: Set<Int>) : DateSpec

    /** Any year, this day of this month: "14 agosto". */
    data class MonthDay(val month: Int, val day: Int) : DateSpec
}

/**
 * Places a word can refer to, resolved offline from the bundled gazetteer. A photo matches when its
 * position resolved to one of the [cityIds], to a city of one of the [regionKeys], or to a
 * [countryCodes] country.
 */
@Immutable
data class PlaceMatch(
    val cityIds: Set<Long> = emptySet(),
    val countryCodes: Set<String> = emptySet(),
    val regionKeys: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = cityIds.isEmpty() && countryCodes.isEmpty() && regionKeys.isEmpty()
}

/** People a word can refer to: the ones the user has named, matched by full name or by any single name. */
@Immutable
data class PersonMatch(val personIds: Set<Long>) {
    val isEmpty: Boolean get() = personIds.isEmpty()
}

/**
 * One free-text word. It matches photos whose file name or recognized text starts with [text], or,
 * when [place] is set, photos taken at that place, or, when [person] is set, photos that person is in.
 */
@Immutable
data class SearchTerm(val text: String, val place: PlaceMatch? = null, val person: PersonMatch? = null)

/**
 * A parsed search: everything must hold (AND), except that several dates are alternatives (OR).
 *
 * [semanticQuery] is set when the free words were also matched against what the photos look like (see
 * SemanticSearchService): the photos that matched are then in the `search_hit` table under that id, and a
 * photo satisfies the free words if its text matches them **or** it is one of those hits.
 */
@Immutable
data class SearchSpec(
    val terms: List<SearchTerm> = emptyList(),
    val dates: List<DateSpec> = emptyList(),
    val filters: LibraryFilters = LibraryFilters.NONE,
    val semanticQuery: Long? = null,
) {
    val isEmpty: Boolean get() = terms.isEmpty() && dates.isEmpty() && !filters.isActive

    /** The free words that are not places or people, joined: what to look for in the photos themselves. Null if there are none. */
    val semanticText: String?
        get() = terms.filter { it.place == null && it.person == null }.joinToString(" ") { it.text }.ifBlank { null }
}

/** Resolves a normalized name ("roma", "new york", "italia") to places, or null. */
fun interface PlaceMatcher {
    fun match(normalizedName: String): PlaceMatch?

    companion object {
        val None = PlaceMatcher { null }
    }
}

/** Resolves a normalized name ("marco", "marco rossi") to the people it names, or null. */
fun interface PersonMatcher {
    fun match(normalizedName: String): PersonMatch?

    companion object {
        val None = PersonMatcher { null }
    }
}
