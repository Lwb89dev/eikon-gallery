package app.eikon.gallery.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import app.eikon.gallery.data.db.LibraryQueryBuilder
import app.eikon.gallery.data.db.MediaDao
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.db.toDomain
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.TimelineGrouping
import app.eikon.gallery.domain.TimelineLayout
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the UI reads. The library is always served from the Room index (never from a live MediaStore
 * cursor), so it renders instantly at launch and only pages of a few dozen rows are in memory.
 */
@Singleton
class MediaRepository @Inject constructor(
    private val dao: MediaDao,
) {
    /**
     * Placeholders give the exact item count up front (needed for the scrollbar and for swiping
     * anywhere in the viewer); [PagingConfig.jumpThreshold] makes a far scroll reload around the new
     * position instead of loading every page in between.
     */
    fun pagedMedia(query: LibraryQuery): Flow<PagingData<MediaItem>> = Pager(
        config = PagingConfig(
            pageSize = PAGE_SIZE,
            prefetchDistance = PAGE_SIZE,
            initialLoadSize = PAGE_SIZE * 2,
            maxSize = PAGE_SIZE * 10,
            jumpThreshold = PAGE_SIZE * 3,
            enablePlaceholders = true,
        ),
        pagingSourceFactory = { dao.pagingSource(LibraryQueryBuilder.media(query).toSupportQuery()) },
    ).flow.map { data -> data.map(MediaEntity::toDomain) }

    /** Number of items in a slice (a tile's subtitle); follows changes live. */
    fun count(query: LibraryQuery): Flow<Int> =
        dao.observeCount(LibraryQueryBuilder.count(query).toSupportQuery())

    /** The newest item of a slice, used as the cover of its tile. */
    fun cover(query: LibraryQuery): Flow<MediaItem?> =
        dao.observeCover(LibraryQueryBuilder.cover(query).toSupportQuery()).map { it?.toDomain() }

    fun timeline(query: LibraryQuery, grouping: TimelineGrouping): Flow<TimelineLayout> =
        dao.observeSectionCounts(LibraryQueryBuilder.sections(query, grouping).toSupportQuery())
            .map { rows -> TimelineLayout.fromCounts(rows.map { it.bucket to it.count }) }

    /** Reflects a confirmed system change immediately instead of waiting for the next sync. */
    suspend fun applyFavorite(ids: Collection<Long>, favorite: Boolean) {
        ids.chunked(CHUNK).forEach { dao.setFavorite(it, favorite) }
    }

    suspend fun removeFromIndex(ids: Collection<Long>) {
        ids.chunked(CHUNK).forEach { dao.deleteByIds(it) }
    }

    private companion object {
        const val PAGE_SIZE = 90

        /** Below SQLite's 999 bound-variable limit. */
        const val CHUNK = 500
    }
}
