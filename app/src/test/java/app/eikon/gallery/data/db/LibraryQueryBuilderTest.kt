package app.eikon.gallery.data.db

import app.eikon.gallery.domain.CategoryFilter
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.SortDirection
import app.eikon.gallery.domain.SortField
import app.eikon.gallery.domain.TimelineGrouping
import app.eikon.gallery.domain.TypeFilter
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
}
