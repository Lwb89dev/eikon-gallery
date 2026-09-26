package app.eikon.gallery.data.db

import app.eikon.gallery.domain.CategoryFilter
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.SortDirection
import app.eikon.gallery.domain.SortField
import app.eikon.gallery.domain.TimelineGrouping
import app.eikon.gallery.domain.TypeFilter
import app.eikon.gallery.domain.search.PersonMatch
import app.eikon.gallery.domain.search.PlaceMatch
import app.eikon.gallery.domain.search.SearchSpec
import app.eikon.gallery.domain.search.SearchTerm
import app.eikon.gallery.domain.search.TimeRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryBuilderTest {
    private val now = 10_000_000_000L
    private val notHidden = "m.id NOT IN (SELECT mediaId FROM hidden_media)"

    private fun media(query: LibraryQuery) = LibraryQueryBuilder.media(query, now)

    @Test
    fun defaultQueryIsEverythingNotHiddenNewestFirst() {
        val sql = media(LibraryQuery())
        assertEquals("SELECT m.* FROM media m WHERE $notHidden ORDER BY m.takenAt DESC, m.id DESC", sql.sql)
        assertTrue(sql.args.isEmpty())
    }

    @Test
    fun sortFieldAndDirectionAreReflectedInOrdering() {
        val query = LibraryQuery(sortField = SortField.DATE_ADDED, direction = SortDirection.OLDEST_FIRST)
        assertTrue(media(query).sql.endsWith("ORDER BY m.addedAt ASC, m.id ASC"))
    }

    @Test
    fun filtersCombineWithAnd() {
        val filters = LibraryFilters(TypeFilter.VIDEOS, favoritesOnly = true, category = CategoryFilter.SCREEN_RECORDINGS)
        val sql = media(LibraryQuery(filters = filters)).sql
        assertTrue("m.isVideo = 1" in sql)
        assertTrue("m.isFavorite = 1" in sql)
        assertTrue("m.isScreenRecording = 1" in sql)
        assertEquals(3, Regex(" AND ").findAll(sql).count())
    }

    @Test
    fun everyCategoryMapsToItsOwnColumn() {
        val expected = mapOf(
            CategoryFilter.SCREENSHOTS to "m.isScreenshot = 1",
            CategoryFilter.SCREEN_RECORDINGS to "m.isScreenRecording = 1",
            CategoryFilter.PANORAMAS to "m.isPanorama = 1",
            CategoryFilter.RAW to "m.isRaw = 1",
        )
        assertEquals(CategoryFilter.entries.toSet(), expected.keys)
        expected.forEach { (category, predicate) ->
            assertTrue(predicate in media(LibraryQuery(filters = LibraryFilters(category = category))).sql)
        }
    }

    @Test
    fun typeFilterAllAddsNoPredicate() {
        assertFalse("isVideo" in media(LibraryQuery(filters = LibraryFilters(TypeFilter.ALL))).sql)
    }

    @Test
    fun albumScopeJoinsMembershipAndBindsTheId() {
        val sql = media(LibraryQuery(scope = LibraryScope.Album(42)))
        assertTrue("JOIN album_item ai ON ai.mediaId = m.id" in sql.sql)
        assertTrue("ai.albumId = ?" in sql.sql)
        assertTrue(notHidden in sql.sql)
        assertEquals(listOf<Any>(42L), sql.args)
    }

    @Test
    fun folderScopeBindsThePathAndNeverInterpolatesIt() {
        val hostile = "DCIM/'; DROP TABLE media; --/"
        val sql = media(LibraryQuery(scope = LibraryScope.Folder(hostile)))
        assertFalse("DROP" in sql.sql)
        assertTrue("m.relativePath = ?" in sql.sql)
        assertEquals(listOf<Any>(hostile), sql.args)
    }

    @Test
    fun recentlyAddedBindsTheCutoff() {
        val sql = media(LibraryQuery(scope = LibraryScope.RecentlyAdded(days = 30)))
        assertTrue("m.addedAt >= ?" in sql.sql)
        assertEquals(listOf<Any>(now - 30 * 86_400_000L), sql.args)
    }

    @Test
    fun hiddenScopeShowsOnlyHiddenAndEveryOtherScopeExcludesThem() {
        val hidden = media(LibraryQuery(scope = LibraryScope.Hidden)).sql
        assertTrue("m.id IN (SELECT mediaId FROM hidden_media)" in hidden)
        assertFalse(notHidden in hidden)

        val others = listOf(LibraryScope.Everything, LibraryScope.Album(1), LibraryScope.Folder("x/"), LibraryScope.RecentlyAdded())
        others.forEach { scope -> assertTrue("$scope must exclude hidden", notHidden in media(LibraryQuery(scope = scope)).sql) }
    }

    @Test
    fun argumentsFollowPlaceholderOrder() {
        val sql = media(LibraryQuery(scope = LibraryScope.Album(7), filters = LibraryFilters(favoritesOnly = true)))
        assertEquals(1, Regex("\\?").findAll(sql.sql).count())
        assertEquals(listOf<Any>(7L), sql.args)
    }

    @Test
    fun sectionsGroupByLocalDayInTheSameOrderAsTheMediaQuery() {
        val newest = LibraryQueryBuilder.sections(LibraryQuery(), TimelineGrouping.DAY, now).sql
        assertTrue("'%Y-%m-%d'" in newest)
        assertTrue("'localtime'" in newest)
        assertTrue(newest.endsWith("GROUP BY bucket ORDER BY bucket DESC"))

        val oldest = LibraryQuery(direction = SortDirection.OLDEST_FIRST)
        assertTrue(LibraryQueryBuilder.sections(oldest, TimelineGrouping.DAY, now).sql.endsWith("ORDER BY bucket ASC"))
    }

    @Test
    fun monthGroupingUsesYearAndMonthOnly() {
        assertTrue("'%Y-%m'" in LibraryQueryBuilder.sections(LibraryQuery(), TimelineGrouping.MONTH, now).sql)
    }

    @Test
    fun sectionsUseTheSelectedSortColumnAndFilterAndScopeArguments() {
        val query = LibraryQuery(LibraryScope.Album(3), LibraryFilters(TypeFilter.VIDEOS), SortField.DATE_ADDED)
        val sql = LibraryQueryBuilder.sections(query, TimelineGrouping.DAY, now)
        assertTrue("m.addedAt / 1000" in sql.sql)
        assertTrue("m.isVideo = 1" in sql.sql)
        assertEquals(listOf<Any>(3L), sql.args)
    }

    @Test
    fun countAndCoverShareTheSameSliceAsTheMediaQuery() {
        val query = LibraryQuery(LibraryScope.Folder("DCIM/Camera/"), LibraryFilters(favoritesOnly = true))
        val slice = media(query).sql.substringAfter("FROM").substringBefore(" ORDER BY")
        assertTrue(LibraryQueryBuilder.count(query, now).sql.endsWith(slice))
        assertTrue(LibraryQueryBuilder.cover(query, now).sql.contains(slice))
        assertTrue(LibraryQueryBuilder.cover(query, now).sql.endsWith("LIMIT 1"))
        assertEquals(media(query).args, LibraryQueryBuilder.count(query, now).args)
    }

    @Test
    fun aMemoryWithNoPeriodMatchesNothingInsteadOfBeingInvalidSql() {
        // "On this day" for 29 February has no period in a library with no leap year.
        val sql = media(LibraryQuery(scope = LibraryScope.Periods(emptyList())))
        assertTrue(sql.sql, "WHERE 0 AND $notHidden" in sql.sql)
        assertFalse("()" in sql.sql)
        assertTrue(sql.args.isEmpty())
    }

    // --- which lists the analysis changes -----------------------------------------------------------

    private val analysisTables = listOf("media_geo", "face", "media_search", "media_caption")

    private fun readsAnalysisTable(sql: String) = analysisTables.any { Regex("\\b$it\\b").containsMatchIn(sql) }

    private val everySortOfList = listOf(
        LibraryScope.Everything, LibraryScope.Hidden, LibraryScope.Album(1), LibraryScope.Folder("DCIM/Camera/"), LibraryScope.RecentlyAdded(),
        LibraryScope.Between(1, 2), LibraryScope.Semantic(9), LibraryScope.Periods(listOf(TimeRange(1, 2))), LibraryScope.Periods(listOf(TimeRange(1, 2)), personId = 5),
        LibraryScope.Person(3), LibraryScope.Place(city = 4), LibraryScope.Place(region = "IT.07"), LibraryScope.Place(country = "IT"), LibraryScope.Place(unknown = true),
        LibraryScope.Area(1.0, 2.0, 3.0, 4.0),
        LibraryScope.Search(SearchSpec(listOf(SearchTerm("roma", place = PlaceMatch(cityIds = setOf(1L)), person = PersonMatch(setOf(2L))), SearchTerm("cane")), semanticQuery = 7L)),
    )

    private val everySortOfFilter = listOf(
        LibraryFilters.NONE, LibraryFilters(favoritesOnly = true), LibraryFilters(editedOnly = true), LibraryFilters(TypeFilter.VIDEOS),
        LibraryFilters(category = CategoryFilter.RAW),
    )

    @Test
    fun aListIsSaidToReadWhatTheAnalysisStoresExactlyWhenItsSqlDoes() {
        // This is what lets every other list ignore the analysis's writes: if a query ever starts to read one of these tables, its list must follow it too.
        for (scope in everySortOfList) for (filters in everySortOfFilter) {
            val query = LibraryQuery(scope, filters)
            val reads = readsAnalysisTable(media(query).sql)
            assertEquals("$scope $filters: ${media(query).sql}", reads, LibraryQueryBuilder.readsAnalysis(query))
            // the same slice is read by the count, the cover, the sections and the position
            assertEquals(reads, readsAnalysisTable(LibraryQueryBuilder.count(query, now).sql))
            assertEquals(reads, readsAnalysisTable(LibraryQueryBuilder.sections(query, TimelineGrouping.DAY, now).sql))
            assertEquals(reads, readsAnalysisTable(LibraryQueryBuilder.position(query, 1, now).sql))
        }
    }

    @Test
    fun aSearchWithNothingTypedYetStillCountsAsReadingTheAnalysis() {
        assertTrue(LibraryQueryBuilder.readsAnalysis(LibraryQuery(LibraryScope.Search(SearchSpec()))))
    }

    @Test
    fun theLibraryAndItsFiltersAreNotTouchedByTheAnalysis() {
        for (filters in everySortOfFilter) assertFalse(LibraryQueryBuilder.readsAnalysis(LibraryQuery(LibraryScope.Everything, filters)))
    }
}
