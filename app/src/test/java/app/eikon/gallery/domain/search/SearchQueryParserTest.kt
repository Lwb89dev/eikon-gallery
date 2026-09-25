package app.eikon.gallery.domain.search

import app.eikon.gallery.domain.CategoryFilter
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.TypeFilter
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryParserTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val friday = LocalDate.of(2026, 9, 25)

    private val rome = PlaceMatch(cityIds = setOf(3169070L))
    private val newYork = PlaceMatch(cityIds = setOf(5128581L))
    private val italy = PlaceMatch(countryCodes = setOf("IT"))
    private val matcher = PlaceMatcher { name ->
        when (name) {
            "roma", "rome" -> rome
            "new york" -> newYork
            "italia", "italy" -> italy
            else -> null
        }
    }

    private val parser = SearchQueryParser(zone, { friday }, matcher)

    private fun millis(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun range(from: LocalDate, to: LocalDate) = DateSpec.Range(TimeRange(millis(from.year, from.monthValue, from.dayOfMonth), millis(to.year, to.monthValue, to.dayOfMonth)))

    private fun day(y: Int, m: Int, d: Int) = range(LocalDate.of(y, m, d), LocalDate.of(y, m, d).plusDays(1))

    private fun month(y: Int, m: Int) = range(LocalDate.of(y, m, 1), LocalDate.of(y, m, 1).plusMonths(1))

    private fun parse(text: String) = parser.parse(text)

    // --- dates ---------------------------------------------------------------------------------

    @Test
    fun aLoneYearIsThatWholeYear() {
        assertEquals(listOf(range(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1))), parse("2025").dates)
    }

    @Test
    fun monthNameWithYearIsThatMonthInBothLanguages() {
        assertEquals(listOf(month(2025, 8)), parse("agosto 2025").dates)
        assertEquals(listOf(month(2025, 8)), parse("August 2025").dates)
        assertEquals(listOf(month(2025, 8)), parse("2025 agosto").dates)
    }

    @Test
    fun aMonthWithoutAYearMatchesThatMonthInEveryYear() {
        assertEquals(listOf(DateSpec.Months(setOf(8))), parse("agosto").dates)
    }

    @Test
    fun dayAndMonthWithAndWithoutYear() {
        assertEquals(listOf(day(2025, 8, 14)), parse("14 agosto 2025").dates)
        assertEquals(listOf(DateSpec.MonthDay(8, 14)), parse("14 agosto").dates)
        assertEquals(listOf(day(2025, 8, 14)), parse("august 14 2025").dates)
        assertEquals(listOf(DateSpec.MonthDay(8, 14)), parse("august 14").dates)
    }

    @Test
    fun shortMonthsAreOnlyTrustedNextToANumber() {
        assertEquals(listOf(month(2025, 8)), parse("ago 2025").dates)
        assertEquals(listOf(day(2025, 12, 3)), parse("3 dic 2025").dates)
        val alone = parse("ago mar")
        assertTrue(alone.dates.isEmpty())
        assertEquals(listOf("ago", "mar"), alone.terms.map { it.text })
    }

    @Test
    fun numericDateFormats() {
        assertEquals(listOf(day(2025, 8, 14)), parse("2025-08-14").dates)
        assertEquals(listOf(day(2025, 8, 14)), parse("14/08/2025").dates)
        assertEquals(listOf(day(2025, 8, 4)), parse("4.8.2025").dates)
        assertEquals(listOf(month(2025, 8)), parse("2025-08").dates)
        assertEquals(listOf(month(2025, 8)), parse("08/2025").dates)
    }

    @Test
    fun impossibleDatesAreNotInvented() {
        // No 31 February: it falls back to the month, and no invented day is searched for.
        val impossibleDay = parse("31 febbraio 2025")
        assertEquals(listOf(month(2025, 2)), impossibleDay.dates)
        assertTrue(impossibleDay.terms.isEmpty())
        val spec = parse("2025-13-45")
        assertEquals(listOf(range(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1))), spec.dates) // just the year survives
    }

    @Test
    fun relativeDaysWeeksMonthsAndYears() {
        assertEquals(listOf(day(2026, 9, 25)), parse("oggi").dates)
        assertEquals(listOf(day(2026, 9, 24)), parse("ieri").dates)
        assertEquals(listOf(day(2026, 9, 24)), parse("yesterday").dates)
        // Friday 25 September 2026: the week runs Monday 21 to Monday 28.
        assertEquals(listOf(range(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28))), parse("questa settimana").dates)
        assertEquals(listOf(range(LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 21))), parse("la settimana scorsa").dates)
        assertEquals(listOf(month(2026, 9)), parse("this month").dates)
        assertEquals(listOf(month(2026, 8)), parse("mese scorso").dates)
        assertEquals(listOf(range(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))), parse("quest'anno").dates)
        assertEquals(listOf(range(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 1, 1))), parse("last year").dates)
    }

    @Test
    fun seasonsWithAndWithoutAYear() {
        assertEquals(listOf(range(LocalDate.of(2025, 6, 1), LocalDate.of(2025, 9, 1))), parse("estate 2025").dates)
        assertEquals(listOf(DateSpec.Months(setOf(6, 7, 8))), parse("summer").dates)
        // Winter 2025 is the December before plus January and February.
        assertEquals(listOf(range(LocalDate.of(2024, 12, 1), LocalDate.of(2025, 3, 1))), parse("inverno 2025").dates)
    }

    @Test
    fun severalDatesAreKeptAsAlternatives() {
        assertEquals(listOf(month(2025, 8), month(2024, 8)), parse("agosto 2025 agosto 2024").dates)
    }

    // --- types -----------------------------------------------------------------------------------

    @Test
    fun typeWordsBecomeFiltersNotSearchTerms() {
        assertEquals(LibraryFilters(TypeFilter.VIDEOS), parse("video").filters)
        assertEquals(LibraryFilters(TypeFilter.PHOTOS), parse("foto").filters)
        assertEquals(LibraryFilters(favoritesOnly = true), parse("preferiti").filters)
        assertEquals(LibraryFilters(category = CategoryFilter.SCREENSHOTS), parse("screenshot").filters)
        assertEquals(LibraryFilters(category = CategoryFilter.PANORAMAS), parse("panoramiche").filters)
        assertEquals(LibraryFilters(category = CategoryFilter.RAW), parse("raw").filters)
        assertTrue(parse("video preferiti").terms.isEmpty())
    }

    @Test
    fun twoWordKindsAreRecognized() {
        assertEquals(CategoryFilter.SCREEN_RECORDINGS, parse("registrazione schermo").filters.category)
        assertEquals(CategoryFilter.SCREEN_RECORDINGS, parse("screen recordings").filters.category)
    }

    @Test
    fun photosAndVideosTogetherMeansBoth() {
        assertEquals(TypeFilter.ALL, parse("foto e video").filters.type)
    }

    // --- words and places ----------------------------------------------------------------------

    @Test
    fun glueWordsAreDroppedAndTheRestKept() {
        val spec = parse("foto di Marco con il cane")
        assertEquals(TypeFilter.PHOTOS, spec.filters.type)
        assertEquals(listOf("marco", "cane"), spec.terms.map { it.text })
    }

    @Test
    fun plainWordsStayTextTerms() {
        assertEquals(listOf("ricevuta", "ikea"), parse("ricevuta IKEA").terms.map { it.text })
    }

    @Test
    fun aKnownPlaceBecomesATermCarryingItsPlace() {
        val spec = parse("foto a Roma")
        assertEquals(listOf(SearchTerm("roma", rome)), spec.terms)
        assertEquals(TypeFilter.PHOTOS, spec.filters.type)
    }

    @Test
    fun multiWordPlacesAreMatchedAsOne() {
        val spec = parse("new york 2025")
        assertEquals(listOf(SearchTerm("new york", newYork)), spec.terms)
        assertEquals(1, spec.dates.size)
    }

    @Test
    fun countriesAreMatchedByName() {
        assertEquals(listOf(SearchTerm("italia", italy)), parse("Italia").terms)
    }

    @Test
    fun accentsAndCaseDoNotMatter() {
        assertEquals(listOf("citta", "peru"), parse("Città PERÙ").terms.map { it.text })
    }

    @Test
    fun tinyAndLoneNumbersAreIgnoredButLongerDigitsKept() {
        assertEquals(listOf("101010"), parse("5 a 101010").terms.map { it.text })
    }

    @Test
    fun aPlaceAndATextWordCombine() {
        val spec = parse("ricevuta roma agosto 2025")
        assertEquals(listOf("ricevuta", "roma"), spec.terms.map { it.text })
        assertEquals(listOf(month(2025, 8)), spec.dates)
    }

    @Test
    fun emptyAndPureGlueQueriesAreEmpty() {
        assertTrue(parse("").isEmpty)
        assertTrue(parse("   ").isEmpty)
        assertTrue(parse("di la in").isEmpty)
        assertTrue(!parse("foto").isEmpty)
    }

    @Test
    fun duplicateWordsCollapse() {
        assertEquals(listOf("cane"), parse("cane cane CANE").terms.map { it.text })
    }
}
