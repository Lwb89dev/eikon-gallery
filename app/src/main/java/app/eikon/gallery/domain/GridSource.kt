package app.eikon.gallery.domain

import app.eikon.gallery.domain.search.SearchSpec

/** The ready-made collections shown in the Collections screen. */
enum class PresetKind { FAVORITES, RECENT, VIDEOS, SCREENSHOTS, SCREEN_RECORDINGS, PANORAMAS, RAW }

/**
 * What one grid screen displays. Collections are just different views over the same index: a preset
 * is a fixed filter, an album is a membership list, a folder is a MediaStore path, Hidden is the
 * opposite of everything else. Each has a stable string form so it can travel in a navigation route.
 */
sealed interface GridSource {
    data object Library : GridSource
    data class Preset(val kind: PresetKind) : GridSource
    data class Album(val id: Long) : GridSource
    data class Folder(val relativePath: String) : GridSource
    data object Hidden : GridSource

    /** The Search tab. The query text is not part of the source: it changes as the user types. */
    data object Search : GridSource

    /** The photos one person from People appears in. */
    data class Person(val id: Long) : GridSource

    /** The photos of dogs or of cats. */
    data class Pets(val kind: PetKind) : GridSource

    /** The photos taken at one place from Places. */
    data class Place(val scope: LibraryScope.Place) : GridSource

    /** The photos inside a box on the map. */
    data class Area(val scope: LibraryScope.Area) : GridSource

    /** All the photos of a memory (not just the few its slideshow shows). */
    data class Memory(val id: app.eikon.gallery.domain.memories.MemoryId) : GridSource

    /** The photos of a period: a trip or a memory. [label] is what the title shows. */
    data class Period(val startMillis: Long, val endMillis: Long, val label: String? = null) : GridSource

    /** Round-trips through [parse]. */
    fun toArg(): String = when (this) {
        Library -> "library"
        is Preset -> "preset:${kind.name}"
        is Album -> "album:$id"
        is Folder -> "folder:$relativePath"
        Hidden -> "hidden"
        Search -> "search"
        is Person -> "person:$id"
        is Pets -> "pets:${kind.name}"
        is Place -> placeArg(scope)
        is Area -> "area:${scope.minLatitude},${scope.maxLatitude},${scope.minLongitude},${scope.maxLongitude}"
        is Period -> "period:$startMillis:$endMillis" + (label?.let { ":$it" } ?: "")
        is Memory -> "memory:${id.toArg()}"
    }

    /**
     * The query for this source. Only the main library uses the user's saved [filters]; every other
     * source has its own fixed slice. The saved sort direction applies everywhere.
     */
    fun toQuery(filters: LibraryFilters, sortField: SortField, direction: SortDirection): LibraryQuery = when (this) {
        Library -> LibraryQuery(LibraryScope.Everything, filters, sortField, direction)
        is Album -> LibraryQuery(LibraryScope.Album(id), LibraryFilters.NONE, sortField, direction)
        is Person -> LibraryQuery(LibraryScope.Person(id), LibraryFilters.NONE, sortField, direction)
        // The photos are found when the screen opens (see LibraryViewModel); until then this is empty.
        is Pets -> LibraryQuery(LibraryScope.Semantic(0), LibraryFilters.NONE, sortField, direction)
        is Place -> LibraryQuery(scope, LibraryFilters.NONE, sortField, direction)
        is Area -> LibraryQuery(scope, LibraryFilters.NONE, sortField, direction)
        is Period -> LibraryQuery(LibraryScope.Between(startMillis, endMillis), LibraryFilters.NONE, sortField, direction)
        is Memory -> LibraryQuery(LibraryScope.Periods(id.periods(java.time.ZoneId.systemDefault()), id.personId), LibraryFilters.NONE, sortField, direction)
        is Folder -> LibraryQuery(LibraryScope.Folder(relativePath), LibraryFilters.NONE, sortField, direction)
        Hidden -> LibraryQuery(LibraryScope.Hidden, LibraryFilters.NONE, sortField, direction)
        // Search results are built from the typed text (see SearchViewModel); this is the empty state.
        Search -> LibraryQuery(LibraryScope.Search(SearchSpec()), LibraryFilters.NONE, sortField, direction)
        is Preset -> presetQuery(kind, sortField, direction)
    }

    companion object {
        /** Unknown or malformed input falls back to the main library rather than failing. */
        fun parse(arg: String?): GridSource {
            val text = arg ?: return Library
            return when {
                text == "hidden" -> Hidden
                text == "search" -> Search
                text.startsWith("preset:") -> PresetKind.entries.firstOrNull { it.name == text.removePrefix("preset:") }
                    ?.let(::Preset) ?: Library
                text.startsWith("place:") -> parsePlace(text.removePrefix("place:")) ?: Library
                text.startsWith("area:") -> parseArea(text.removePrefix("area:")) ?: Library
                text.startsWith("memory:") -> app.eikon.gallery.domain.memories.MemoryId.parse(text.removePrefix("memory:"))?.let(::Memory) ?: Library
                text.startsWith("period:") -> parsePeriod(text.removePrefix("period:")) ?: Library
                text.startsWith("pets:") -> PetKind.entries.firstOrNull { it.name == text.removePrefix("pets:") }?.let(::Pets) ?: Library
                text.startsWith("person:") -> text.removePrefix("person:").toLongOrNull()?.let(::Person) ?: Library
                text.startsWith("album:") -> text.removePrefix("album:").toLongOrNull()?.let(::Album) ?: Library
                text.startsWith("folder:") -> Folder(text.removePrefix("folder:"))
                else -> Library
            }
        }

        private fun placeArg(scope: LibraryScope.Place): String = when {
            scope.city != null -> "place:city:${scope.city}"
            scope.region != null -> "place:region:${scope.region}"
            scope.country != null -> "place:country:${scope.country}"
            else -> "place:unknown"
        }

        private fun parsePlace(text: String): GridSource? {
            val (kind, value) = text.split(':', limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
            val scope = when {
                kind == "city" -> value.toLongOrNull()?.let { LibraryScope.Place(city = it) }
                kind == "region" && value.isNotEmpty() -> LibraryScope.Place(region = value)
                kind == "country" && value.isNotEmpty() -> LibraryScope.Place(country = value)
                kind == "unknown" -> LibraryScope.Place(unknown = true)
                else -> null
            }
            return scope?.let(::Place)
        }

        private fun parseArea(text: String): GridSource? {
            val v = text.split(',').map { it.toDoubleOrNull() ?: return null }
            return if (v.size == 4) Area(LibraryScope.Area(v[0], v[1], v[2], v[3])) else null
        }

        private fun parsePeriod(text: String): GridSource? {
            val parts = text.split(':', limit = 3)
            val start = parts.getOrNull(0)?.toLongOrNull() ?: return null
            val end = parts.getOrNull(1)?.toLongOrNull() ?: return null
            return Period(start, end, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
        }

        fun presetQuery(kind: PresetKind, sortField: SortField, direction: SortDirection): LibraryQuery {
            val everything = LibraryScope.Everything
            return when (kind) {
                PresetKind.FAVORITES -> LibraryQuery(everything, LibraryFilters(favoritesOnly = true), sortField, direction)
                PresetKind.VIDEOS -> LibraryQuery(everything, LibraryFilters(type = TypeFilter.VIDEOS), sortField, direction)
                PresetKind.SCREENSHOTS -> LibraryQuery(everything, LibraryFilters(category = CategoryFilter.SCREENSHOTS), sortField, direction)
                PresetKind.SCREEN_RECORDINGS -> LibraryQuery(everything, LibraryFilters(category = CategoryFilter.SCREEN_RECORDINGS), sortField, direction)
                PresetKind.PANORAMAS -> LibraryQuery(everything, LibraryFilters(category = CategoryFilter.PANORAMAS), sortField, direction)
                PresetKind.RAW -> LibraryQuery(everything, LibraryFilters(category = CategoryFilter.RAW), sortField, direction)
                // "Recently added" is about when a file arrived, so it is ordered by that regardless of the saved sort.
                PresetKind.RECENT -> LibraryQuery(LibraryScope.RecentlyAdded(), LibraryFilters.NONE, SortField.DATE_ADDED, direction)
            }
        }
    }
}
