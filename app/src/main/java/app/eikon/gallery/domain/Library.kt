package app.eikon.gallery.domain

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import app.eikon.gallery.domain.search.SearchSpec

enum class SortField { DATE_TAKEN, DATE_ADDED }

enum class SortDirection { NEWEST_FIRST, OLDEST_FIRST }

enum class TypeFilter { ALL, PHOTOS, VIDEOS }

/** Special kinds of media. Detection is heuristic, see MediaClassifier. */
enum class CategoryFilter { SCREENSHOTS, SCREEN_RECORDINGS, PANORAMAS, RAW }

/**
 * Filters that combine with AND: e.g. videos + favorites, or favorite RAW photos. Type and category
 * are each at most one choice, which keeps combinations meaningful (a video is never a screenshot).
 */
@Immutable
data class LibraryFilters(
    val type: TypeFilter = TypeFilter.ALL,
    val favoritesOnly: Boolean = false,
    val category: CategoryFilter? = null,
    /** Only photos that have an edit made in eikon. */
    val editedOnly: Boolean = false,
) {
    val isActive: Boolean get() = this != NONE

    companion object {
        val NONE = LibraryFilters()
    }
}

/** Which slice of the library a screen shows. Hidden items only ever appear in [Hidden]. */
sealed interface LibraryScope {
    data object Everything : LibraryScope

    /** A user-made (virtual) album; membership lives in eikon's database, files are untouched. */
    data class Album(val id: Long) : LibraryScope

    /** A device folder as reported by MediaStore (`relative_path`), e.g. "DCIM/Camera/". */
    data class Folder(val relativePath: String) : LibraryScope

    /** Added within the last [days] days, regardless of when the photo was taken. */
    data class RecentlyAdded(val days: Int = RECENT_DAYS) : LibraryScope

    data object Hidden : LibraryScope

    /** Photos matching what the user typed in Search (text, place and date; hidden ones never appear). */
    data class Search(val spec: SearchSpec) : LibraryScope

    /** Photos a person appears in (the People collection); hidden photos never appear. */
    data class Person(val id: Long) : LibraryScope

    /**
     * Photos taken at a place, grouped as in Places: in one [city], one [region] (`IT.07`) or one [country]; or, with
     * [unknown], geotagged photos too far from any known city to have a place name. Exactly one of the four is set.
     */
    data class Place(
        val city: Long? = null,
        val region: String? = null,
        val country: String? = null,
        val unknown: Boolean = false,
    ) : LibraryScope

    /** Photos whose position is inside a box (a cluster tapped on the map). */
    data class Area(val minLatitude: Double, val maxLatitude: Double, val minLongitude: Double, val maxLongitude: Double) : LibraryScope

    /** Photos taken in `[startMillis, endMillis)`, whether or not they have a position (a trip, a season, a week). */
    data class Between(val startMillis: Long, val endMillis: Long) : LibraryScope

    /** Photos taken in any of several periods, optionally only those a person is in (a memory); hidden photos never appear. */
    data class Periods(val ranges: List<app.eikon.gallery.domain.search.TimeRange>, val personId: Long? = null) : LibraryScope

    /** Photos stored under a semantic query id (for example the photos of dogs); hidden photos never appear. */
    data class Semantic(val queryId: Long) : LibraryScope

    companion object {
        const val RECENT_DAYS = 30
    }
}

/** How the timeline is split under date headers. Coarser at high grid densities. */
enum class TimelineGrouping {
    DAY,
    MONTH,
    ;

    companion object {
        private const val MONTH_MIN_COLUMNS = 6

        /** Dense grids show hundreds of items per screen; a header per day would be mostly headers. */
        fun forColumns(columns: Int): TimelineGrouping = if (columns >= MONTH_MIN_COLUMNS) MONTH else DAY
    }
}

/** What a grid screen shows: which slice, filtered how, in which order. */
@Immutable
data class LibraryQuery(
    val scope: LibraryScope = LibraryScope.Everything,
    val filters: LibraryFilters = LibraryFilters.NONE,
    val sortField: SortField = SortField.DATE_TAKEN,
    val direction: SortDirection = SortDirection.NEWEST_FIRST,
)

/**
 * A photo or video as shown by the UI. It is a view of one MediaStore row: the file itself is never
 * copied, [uri] always points back at MediaStore.
 */
@Immutable
data class MediaItem(
    val id: Long,
    val displayName: String,
    val mimeType: String,
    val isVideo: Boolean,
    /** Epoch millis. Falls back to modification/add time when the file has no capture date. */
    val takenAt: Long,
    val addedAt: Long,
    val modifiedAt: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val relativePath: String?,
    val bucketName: String?,
    val isFavorite: Boolean,
) {
    val uri: Uri get() = mediaContentUri(id, isVideo)
}

/** The MediaStore content URI of an image or video id on the merged external volume. */
fun mediaContentUri(id: Long, isVideo: Boolean): Uri {
    val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    return ContentUris.withAppendedId(base, id)
}
