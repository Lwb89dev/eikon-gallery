package app.eikon.gallery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridSourceTest {
    @Test
    fun everySourceRoundTripsThroughItsStringForm() {
        val sources = listOf(
            GridSource.Library,
            GridSource.Hidden,
            GridSource.Album(42),
            GridSource.Folder("DCIM/Camera/"),
            GridSource.Folder("Pictures/My: odd folder/"),
        ) + PresetKind.entries.map(GridSource::Preset)
        sources.forEach { assertEquals(it, GridSource.parse(it.toArg())) }
    }

    @Test
    fun missingOrMalformedInputFallsBackToTheLibrary() {
        listOf(null, "", "nonsense", "album:", "album:abc", "preset:NOPE").forEach {
            assertEquals("for $it", GridSource.Library, GridSource.parse(it))
        }
    }

    @Test
    fun onlyTheLibraryUsesTheSavedFilters() {
        val saved = LibraryFilters(TypeFilter.VIDEOS, favoritesOnly = true)
        val order = SortField.DATE_TAKEN to SortDirection.NEWEST_FIRST
        fun query(source: GridSource) = source.toQuery(saved, order.first, order.second)

        assertEquals(saved, query(GridSource.Library).filters)
        assertEquals(LibraryFilters.NONE, query(GridSource.Hidden).filters)
        assertEquals(LibraryFilters.NONE, query(GridSource.Album(1)).filters)
        assertEquals(LibraryFilters.NONE, query(GridSource.Folder("x/")).filters)
    }

    @Test
    fun scopesFollowTheSource() {
        fun scope(source: GridSource) = source.toQuery(LibraryFilters.NONE, SortField.DATE_TAKEN, SortDirection.NEWEST_FIRST).scope
        assertEquals(LibraryScope.Everything, scope(GridSource.Library))
        assertEquals(LibraryScope.Hidden, scope(GridSource.Hidden))
        assertEquals(LibraryScope.Album(9), scope(GridSource.Album(9)))
        assertEquals(LibraryScope.Folder("a/"), scope(GridSource.Folder("a/")))
    }

    @Test
    fun presetsMapToTheirFixedFilters() {
        fun filters(kind: PresetKind) = GridSource.presetQuery(kind, SortField.DATE_TAKEN, SortDirection.NEWEST_FIRST).filters
        assertTrue(filters(PresetKind.FAVORITES).favoritesOnly)
        assertEquals(TypeFilter.VIDEOS, filters(PresetKind.VIDEOS).type)
        assertEquals(CategoryFilter.SCREENSHOTS, filters(PresetKind.SCREENSHOTS).category)
        assertEquals(CategoryFilter.SCREEN_RECORDINGS, filters(PresetKind.SCREEN_RECORDINGS).category)
        assertEquals(CategoryFilter.PANORAMAS, filters(PresetKind.PANORAMAS).category)
        assertEquals(CategoryFilter.RAW, filters(PresetKind.RAW).category)
    }

    @Test
    fun recentlyAddedIsAlwaysOrderedByDateAdded() {
        val query = GridSource.presetQuery(PresetKind.RECENT, SortField.DATE_TAKEN, SortDirection.OLDEST_FIRST)
        assertEquals(LibraryScope.RecentlyAdded(), query.scope)
        assertEquals(SortField.DATE_ADDED, query.sortField)
        assertEquals(SortDirection.OLDEST_FIRST, query.direction)
    }
}
