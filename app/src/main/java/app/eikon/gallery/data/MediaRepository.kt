package app.eikon.gallery.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import app.eikon.gallery.data.db.LibraryQueryBuilder
import app.eikon.gallery.data.db.MediaDao
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.db.toDomain
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
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
        pagingSourceFactory = {
            val sql = LibraryQueryBuilder.media(query).toSupportQuery()
            if (query.followsAnalysis()) dao.pagingSource(sql) else dao.pagingSourceSteady(sql)
        },
    ).flow.map { data -> data.map(MediaEntity::toDomain) }

    /** Number of items in a slice (a tile's subtitle); follows changes live. */
    fun count(query: LibraryQuery): Flow<Int> {
        val sql = LibraryQueryBuilder.count(query).toSupportQuery()
        return if (query.followsAnalysis()) dao.observeCount(sql) else dao.observeCountSteady(sql)
    }

    /** Where [mediaId] is in the list of [query] (0 = first), once it is there; follows the index, so a photo that was not indexed yet appears when it is. */
    fun positionOf(query: LibraryQuery, mediaId: Long): Flow<Int?> {
        val sql = LibraryQueryBuilder.position(query, mediaId).toSupportQuery()
        return if (query.followsAnalysis()) dao.observePosition(sql) else dao.observePositionSteady(sql)
    }

    /** The newest item of a slice, used as the cover of its tile. */
    fun cover(query: LibraryQuery): Flow<MediaItem?> {
        val sql = LibraryQueryBuilder.cover(query).toSupportQuery()
        return (if (query.followsAnalysis()) dao.observeCover(sql) else dao.observeCoverSteady(sql)).map { it?.toDomain() }
    }

    fun timeline(query: LibraryQuery, grouping: TimelineGrouping): Flow<TimelineLayout> {
        val sql = LibraryQueryBuilder.sections(query, grouping).toSupportQuery()
        val rows = if (query.followsAnalysis()) dao.observeSectionCounts(sql) else dao.observeSectionCountsSteady(sql)
        return rows.map { list -> TimelineLayout.fromCounts(list.map { it.bucket to it.count }, grouping) }
    }

    /**
     * Emits when the analysis stores something a search reads (text, places, faces, captions). A search's list does not follow those tables by itself (see [followsAnalysis]): whoever shows it
     * decides when to redo it, which is when the person is not scrolling it.
     */
    fun analysisChanges(): Flow<Int> = dao.observeAnalysisChanges(SimpleSQLiteQuery("SELECT 0"))

    /**
     * Whether a list is redone by itself as the analysis stores more. A place, an area or a person is (there is little on screen to disturb, and the list grows as photos are analysed); a search is
     * not: its results would shift under the finger while it is being scrolled, and redoing it after every photo made scrolling stall. A list the analysis does not touch is never redone for it.
     */
    private fun LibraryQuery.followsAnalysis() = LibraryQueryBuilder.readsAnalysis(this) && scope !is LibraryScope.Search

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
