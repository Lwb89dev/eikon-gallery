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

    /** Round-trips through [parse]. */
    fun toArg(): String = when (this) {
        Library -> "library"
        is Preset -> "preset:${kind.name}"
        is Album -> "album:$id"
        is Folder -> "folder:$relativePath"
        Hidden -> "hidden"
        Search -> "search"
    }

    /**
     * The query for this source. Only the main library uses the user's saved [filters]; every other
     * source has its own fixed slice. The saved sort direction applies everywhere.
     */
    fun toQuery(filters: LibraryFilters, sortField: SortField, direction: SortDirection): LibraryQuery = when (this) {
        Library -> LibraryQuery(LibraryScope.Everything, filters, sortField, direction)
        is Album -> LibraryQuery(LibraryScope.Album(id), LibraryFilters.NONE, sortField, direction)
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
                text.startsWith("album:") -> text.removePrefix("album:").toLongOrNull()?.let(::Album) ?: Library
                text.startsWith("folder:") -> Folder(text.removePrefix("folder:"))
                else -> Library
            }
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
