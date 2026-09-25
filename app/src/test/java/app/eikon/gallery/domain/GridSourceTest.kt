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
            GridSource.Person(7),
            GridSource.Place(LibraryScope.Place(city = 3169070)),
            GridSource.Place(LibraryScope.Place(region = "IT.07")),
            GridSource.Place(LibraryScope.Place(country = "IT")),
            GridSource.Place(LibraryScope.Place(unknown = true)),
            GridSource.Area(LibraryScope.Area(41.1, 42.05, -12.5, 12.9)),
            GridSource.Period(1_000, 2_000),
            GridSource.Period(1_000, 2_000, "Sicilia: agosto"),
            GridSource.Memory(app.eikon.gallery.domain.memories.MemoryId.OnThisDay(9, 25, listOf(2024, 2022))),
            GridSource.Memory(app.eikon.gallery.domain.memories.MemoryId.WithPerson(4, 2025)),
        ) + PresetKind.entries.map(GridSource::Preset) + PetKind.entries.map(GridSource::Pets)
        sources.forEach { assertEquals(it, GridSource.parse(it.toArg())) }
    }

    @Test
    fun missingOrMalformedInputFallsBackToTheLibrary() {
        listOf(null, "", "nonsense", "album:", "album:abc", "preset:NOPE", "person:x", "person:", "pets:BIRD", "place:", "place:city:x", "place:region:", "area:1,2,3", "period:x:2", "period:1", "memory:", "memory:nope:1").forEach {
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
        assertEquals(LibraryFilters.NONE, query(GridSource.Person(1)).filters)
    }

    @Test
    fun aPersonSourceIsThePersonsScope() {
        val query = GridSource.Person(9).toQuery(LibraryFilters.NONE, SortField.DATE_TAKEN, SortDirection.NEWEST_FIRST)
        assertEquals(LibraryScope.Person(9), query.scope)
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
