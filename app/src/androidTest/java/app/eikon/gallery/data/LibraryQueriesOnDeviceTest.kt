package app.eikon.gallery.data

import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.db.LibraryQueryBuilder
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.TimelineGrouping
import app.eikon.gallery.domain.TimelineLayout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library queries run through the real DAO (Paging source, section counts, count, cover) on the
 * device's own SQLite, which is what ships. The JVM tests cover the SQL logic; this checks the Room
 * wiring and that `localtime` grouping works on Android.
 */
@RunWith(AndroidJUnit4::class)
class LibraryQueriesOnDeviceTest {
    private lateinit var db: EikonDatabase

    @Before
    fun setUp() {
        db = inMemoryDatabase()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedThreeDays() = db.mediaDao().upsertAll(
        listOf(
            media(1, day = 10), media(2, day = 10, video = true, favorite = true), media(3, day = 10),
            media(4, day = 9, favorite = true), media(5, day = 5), media(6, day = 5),
        ),
    )

    private suspend fun pageIds(query: LibraryQuery): List<Long> {
        val source = db.mediaDao().pagingSource(LibraryQueryBuilder.media(query).toSupportQuery())
        val page = source.load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        return page.data.map { it.id }
    }

    @Test
    fun pagingSourceServesTheQueryOrder() = runTest {
        seedThreeDays()
        assertEquals(listOf(3L, 2L, 1L, 4L, 6L, 5L), pageIds(LibraryQuery()))
    }

    @Test
    fun sectionCountsGroupByDayOnTheDevice() = runTest {
        seedThreeDays()
        val rows = db.mediaDao().observeSectionCounts(LibraryQueryBuilder.sections(LibraryQuery(), TimelineGrouping.DAY).toSupportQuery()).first()
        assertEquals(listOf(3, 1, 2), rows.map { it.count })
        assertEquals(6, TimelineLayout.fromCounts(rows.map { it.bucket to it.count }).mediaCount)
    }

    @Test
    fun hiddenItemsAreExcludedEverywhereButTheirOwnScope() = runTest {
        seedThreeDays()
        db.hiddenDao().hide(listOf(app.eikon.gallery.data.db.HiddenMediaEntity(3, 0)))
        assertEquals(listOf(2L, 1L, 4L, 6L, 5L), pageIds(LibraryQuery()))
        assertEquals(listOf(3L), pageIds(LibraryQuery(scope = LibraryScope.Hidden)))
        assertEquals(5, db.mediaDao().observeCount(LibraryQueryBuilder.count(LibraryQuery()).toSupportQuery()).first())
    }

    @Test
    fun combinedFiltersCountAndCoverAgree() = runTest {
        seedThreeDays()
        val query = LibraryQuery(filters = LibraryFilters(favoritesOnly = true))
        assertEquals(2, db.mediaDao().observeCount(LibraryQueryBuilder.count(query).toSupportQuery()).first())
        assertEquals(2L, db.mediaDao().observeCover(LibraryQueryBuilder.cover(query).toSupportQuery()).first()?.id)
    }

    @Test
    fun albumScopeJoinsOnDevice() = runTest {
        seedThreeDays()
        val dao = db.albumDao()
        val id = dao.create("Trip", 0)
        dao.addItems(listOf(4L, 5L).map { app.eikon.gallery.data.db.AlbumItemEntity(id, it, 0) })
        assertEquals(listOf(4L, 5L), pageIds(LibraryQuery(scope = LibraryScope.Album(id))))
    }
}
