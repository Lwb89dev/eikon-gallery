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

/**
 * One free-text word. It matches photos whose file name or recognized text starts with [text], or,
 * when [place] is set, photos taken at that place.
 */
@Immutable
data class SearchTerm(val text: String, val place: PlaceMatch? = null)

/** A parsed search: everything must hold (AND), except that several dates are alternatives (OR). */
@Immutable
data class SearchSpec(
    val terms: List<SearchTerm> = emptyList(),
    val dates: List<DateSpec> = emptyList(),
    val filters: LibraryFilters = LibraryFilters.NONE,
) {
    val isEmpty: Boolean get() = terms.isEmpty() && dates.isEmpty() && !filters.isActive
}

/** Resolves a normalized name ("roma", "new york", "italia") to places, or null. */
fun interface PlaceMatcher {
    fun match(normalizedName: String): PlaceMatch?

    companion object {
        val None = PlaceMatcher { null }
    }
}
