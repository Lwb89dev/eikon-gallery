package app.eikon.gallery.domain.search

import app.eikon.gallery.domain.CategoryFilter
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.TypeFilter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import app.eikon.gallery.domain.search.SearchLexicon.Relative
import app.eikon.gallery.domain.search.SearchLexicon.Season
import app.eikon.gallery.domain.search.SearchLexicon.TypeWord

/**
 * Turns what the user typed ("foto a Roma agosto 2025", "ricevuta IKEA", "video preferiti") into a
 * [SearchSpec]: dates, type filters, places, and remaining free-text words. It is deliberately plain,
 * explainable rules with no model: what a word means is decided by the tables in [SearchLexicon].
 *
 * Time is injected ([zone], [today]) so parsing is deterministic in tests. Weeks start on Monday and
 * seasons are the northern-hemisphere meteorological ones.
 */
class SearchQueryParser(
    private val zone: ZoneId,
    private val today: () -> LocalDate,
    private val places: PlaceMatcher = PlaceMatcher.None,
    private val people: PersonMatcher = PersonMatcher.None,
) {
    fun parse(input: String): SearchSpec {
        val state = ParseState()
        val withoutNumericDates = extractNumericDates(SearchLexicon.normalize(input), state)
        val tokens = withoutNumericDates.replace(Regex("[/.\\-]+"), " ").split(' ').filter { it.isNotBlank() }
        var index = 0
        while (index < tokens.size) index += consume(tokens, index, state)
        return SearchSpec(state.terms.toList(), state.dates.distinct(), state.filters())
    }

    private class ParseState {
        val terms = LinkedHashSet<SearchTerm>()
        val dates = mutableListOf<DateSpec>()
        var type = TypeFilter.ALL
        var favoritesOnly = false
        var category: CategoryFilter? = null

        fun filters() = LibraryFilters(type, favoritesOnly, category)
    }

    // --- Dates written with digits ----------------------------------------------------------------

    private fun extractNumericDates(text: String, state: ParseState): String {
        var result = text
        result = replaceDates(result, ISO_DAY, state) { g -> dayRange(g[1].toInt(), g[2].toInt(), g[3].toInt()) }
        result = replaceDates(result, EU_DAY, state) { g -> dayRange(g[3].toInt(), g[2].toInt(), g[1].toInt()) }
        result = replaceDates(result, YEAR_MONTH, state) { g -> monthRange(g[1].toInt(), g[2].toInt()) }
        result = replaceDates(result, MONTH_YEAR, state) { g -> monthRange(g[2].toInt(), g[1].toInt()) }
        return result
    }

    /** Replaces every valid date matched by [pattern] with a space, recording it; invalid ones stay as text. */
    private fun replaceDates(text: String, pattern: Regex, state: ParseState, toRange: (List<String>) -> TimeRange?): String =
        pattern.replace(text) { match ->
            val range = toRange(match.groupValues)
            if (range == null) {
                match.value
            } else {
                state.dates += DateSpec.Range(range)
                " "
            }
        }

    private fun dayRange(year: Int, month: Int, day: Int): TimeRange? {
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        return range(date, date.plusDays(1))
    }

    private fun monthRange(year: Int, month: Int): TimeRange? {
        val first = runCatching { LocalDate.of(year, month, 1) }.getOrNull() ?: return null
        return range(first, first.plusMonths(1))
    }

    // --- One step of the scan ---------------------------------------------------------------------

    /** Handles the words starting at [i]; returns how many were used (always at least one). */
    private fun consume(t: List<String>, i: Int, state: ParseState): Int =
        consumeRelative(t, i, state)
            ?: consumeTypeWord(t, i, state)
            ?: consumeDayThenMonth(t, i, state)
            ?: consumeMonthFirst(t, i, state)
            ?: consumeYearThenMonth(t, i, state)
            ?: consumeSeason(t, i, state)
            ?: consumeYear(t, i, state)
            ?: consumeWord(t, i, state)

    private fun consumeRelative(t: List<String>, i: Int, state: ParseState): Int? {
        for (length in MAX_PHRASE downTo 1) {
            val relative = SearchLexicon.relativePhrases[slice(t, i, length)] ?: continue
            state.dates += DateSpec.Range(relativeRange(relative))
            return length
        }
        return null
    }

    private fun consumeTypeWord(t: List<String>, i: Int, state: ParseState): Int? {
        for (length in MAX_PHRASE downTo 1) {
            val word = SearchLexicon.typePhrases[slice(t, i, length)] ?: continue
            apply(word, state)
            return length
        }
        return null
    }

    private fun apply(word: TypeWord, state: ParseState) {
        when (word) {
            is TypeWord.OfType -> state.type = if (state.type != TypeFilter.ALL && state.type != word.type) TypeFilter.ALL else word.type
            TypeWord.Favorites -> state.favoritesOnly = true
            is TypeWord.OfKind -> state.category = word.kind
        }
    }

    /** "14 agosto", "14 agosto 2025": a day number followed by a month name. */
    private fun consumeDayThenMonth(t: List<String>, i: Int, state: ParseState): Int? {
        val day = dayNumber(t[i]) ?: return null
        val month = monthOf(t.getOrNull(i + 1), trustShort = true) ?: return null
        val year = yearOf(t.getOrNull(i + 2))
        if (year == null) {
            state.dates += DateSpec.MonthDay(month, day)
            return 2
        }
        val exact = dayRangeOrNull(year, month, day) ?: return null
        state.dates += exact
        return 3
    }

    /** "agosto", "agosto 2025", "august 14", "august 14 2025", and short forms next to a year or day. */
    private fun consumeMonthFirst(t: List<String>, i: Int, state: ParseState): Int? {
        val next = t.getOrNull(i + 1)
        val hasContext = yearOf(next) != null || (next != null && dayNumber(next) != null)
        val month = monthOf(t[i], trustShort = hasContext) ?: return null
        val year = yearOf(next)
        if (year != null) {
            state.dates += monthDate(year, month)
            return 2
        }
        val day = next?.let(::dayNumber)
        if (day != null) return consumeEnglishDay(t, i, month, day, state)
        state.dates += DateSpec.Months(setOf(month))
        return 1
    }

    /** "august 14" / "august 14 2025"; an impossible day falls back to the month. */
    private fun consumeEnglishDay(t: List<String>, i: Int, month: Int, day: Int, state: ParseState): Int {
        val year = yearOf(t.getOrNull(i + 2))
        val exact = year?.let { dayRangeOrNull(it, month, day) }
        state.dates += exact ?: DateSpec.MonthDay(month, day)
        return if (exact != null) 3 else 2
    }

    /** "2025 agosto": year first, then a full month name. */
    private fun consumeYearThenMonth(t: List<String>, i: Int, state: ParseState): Int? {
        val year = yearOf(t[i]) ?: return null
        val month = monthOf(t.getOrNull(i + 1), trustShort = false) ?: return null
        state.dates += monthDate(year, month)
        return 2
    }

    private fun consumeSeason(t: List<String>, i: Int, state: ParseState): Int? {
        val season = SearchLexicon.seasons[t[i]] ?: return null
        val year = yearOf(t.getOrNull(i + 1))
        if (year == null) {
            state.dates += DateSpec.Months(season.months)
            return 1
        }
        state.dates += DateSpec.Range(seasonRange(season, year))
        return 2
    }

    private fun consumeYear(t: List<String>, i: Int, state: ParseState): Int? {
        val year = yearOf(t[i]) ?: return null
        state.dates += DateSpec.Range(range(LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1)))
        return 1
    }

    /**
     * Anything else: a person the user named or a place (the longest run of words either knows), or a plain search
     * word. A person is tried first, because the user chose that name.
     */
    private fun consumeWord(t: List<String>, i: Int, state: ParseState): Int {
        for (length in MAX_PLACE_WORDS downTo 1) {
            if (i + length > t.size) continue
            val words = t.subList(i, i + length)
            if (words.all { it in SearchLexicon.stopWords }) continue
            val name = words.joinToString(" ")
            val term = personTerm(name) ?: placeTerm(name) ?: continue
            state.terms += term
            return length
        }
        addPlainTerm(t[i], state)
        return 1
    }

    private fun personTerm(name: String): SearchTerm? = people.match(name)?.takeUnless { it.isEmpty }?.let { SearchTerm(name, person = it) }

    private fun placeTerm(name: String): SearchTerm? = places.match(name)?.takeUnless { it.isEmpty }?.let { SearchTerm(name, place = it) }

    private fun addPlainTerm(word: String, state: ParseState) {
        if (word in SearchLexicon.stopWords) return
        if (word.length < MIN_TERM_LENGTH) return
        // "1" or "13" as a prefix would match almost every file name, so short bare numbers carry no meaning.
        if (word.all { it.isDigit() } && word.length < MIN_NUMBER_LENGTH) return
        state.terms += SearchTerm(word)
    }

    // --- Helpers ---------------------------------------------------------------------------------------

    private fun slice(t: List<String>, from: Int, length: Int): List<String> =
        if (from + length <= t.size) t.subList(from, from + length) else emptyList()

    private fun dayNumber(token: String): Int? = if (token.length <= 2) token.toIntOrNull()?.takeIf { it in 1..31 } else null

    private fun yearOf(token: String?): Int? =
        if (token != null && token.length == 4) token.toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR } else null

    private fun monthOf(token: String?, trustShort: Boolean): Int? {
        if (token == null) return null
        return SearchLexicon.monthsFull[token] ?: if (trustShort) SearchLexicon.monthsShort[token] else null
    }

    private fun monthDate(year: Int, month: Int): DateSpec = DateSpec.Range(monthRange(year, month)!!)

    private fun dayRangeOrNull(year: Int, month: Int, day: Int): DateSpec? =
        dayRange(year, month, day)?.let { DateSpec.Range(it) }

    private fun range(start: LocalDate, endExclusive: LocalDate) = TimeRange(millis(start), millis(endExclusive))

    private fun millis(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun seasonRange(season: Season, year: Int): TimeRange = when (season) {
        Season.SPRING -> range(LocalDate.of(year, 3, 1), LocalDate.of(year, 6, 1))
        Season.SUMMER -> range(LocalDate.of(year, 6, 1), LocalDate.of(year, 9, 1))
        Season.AUTUMN -> range(LocalDate.of(year, 9, 1), LocalDate.of(year, 12, 1))
        Season.WINTER -> range(LocalDate.of(year - 1, 12, 1), LocalDate.of(year, 3, 1))
    }

    private fun relativeRange(relative: Relative): TimeRange {
        val now = today()
        val monday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val firstOfMonth = now.withDayOfMonth(1)
        val firstOfYear = now.withDayOfYear(1)
        return when (relative) {
            Relative.TODAY -> range(now, now.plusDays(1))
            Relative.YESTERDAY -> range(now.minusDays(1), now)
            Relative.THIS_WEEK -> range(monday, monday.plusDays(WEEK_DAYS))
            Relative.LAST_WEEK -> range(monday.minusDays(WEEK_DAYS), monday)
            Relative.THIS_MONTH -> range(firstOfMonth, firstOfMonth.plusMonths(1))
            Relative.LAST_MONTH -> range(firstOfMonth.minusMonths(1), firstOfMonth)
            Relative.THIS_YEAR -> range(firstOfYear, firstOfYear.plusYears(1))
            Relative.LAST_YEAR -> range(firstOfYear.minusYears(1), firstOfYear)
        }
    }

    private companion object {
        const val MAX_PHRASE = 2
        const val MAX_PLACE_WORDS = 3
        const val MIN_TERM_LENGTH = 2
        const val MIN_NUMBER_LENGTH = 3
        const val MIN_YEAR = 1900
        const val MAX_YEAR = 2100
        const val WEEK_DAYS = 7L

        val ISO_DAY = Regex("(?<![\\d])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![\\d])")
        val EU_DAY = Regex("(?<![\\d])(\\d{1,2})[/.\\-](\\d{1,2})[/.\\-](\\d{4})(?![\\d])")
        val YEAR_MONTH = Regex("(?<![\\d])(\\d{4})-(\\d{1,2})(?![\\d-])")
        val MONTH_YEAR = Regex("(?<![\\d/.\\-])(\\d{1,2})/(\\d{4})(?![\\d])")
    }
}
