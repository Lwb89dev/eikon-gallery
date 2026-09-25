package app.eikon.gallery.data.indexing

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.IndexDao
import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.db.IndexStateEntity
import app.eikon.gallery.data.db.IndexStatus
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.db.MediaGeoEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** How far one analysis step has got: [done] of [total] photos. */
data class StageProgress(val done: Int, val total: Int) {
    val isComplete: Boolean get() = total > 0 && done >= total
}

/** The database side of the background analysis: what is left to do, and recording what was learned. */
@Singleton
class IndexingRepository @Inject constructor(
    private val dao: IndexDao,
    private val clock: Clock,
) : WorkQueue {
    fun progress(stage: IndexStage): Flow<StageProgress> =
        combine(dao.observePhotoCount(), dao.observeFinished(stage.name, MAX_ATTEMPTS)) { total, done ->
            StageProgress(done.coerceAtMost(total), total)
        }

    /** Next photos to analyse for [stage]: newest first, never tried or failed fewer than [MAX_ATTEMPTS] times. */
    override suspend fun pending(stage: IndexStage, limit: Int): List<MediaEntity> = dao.pending(stage.name, MAX_ATTEMPTS, limit)

    override suspend fun markDone(mediaId: Long, stage: IndexStage) = record(mediaId, stage, IndexStatus.DONE, attempts = 0)

    /** Nothing to extract from this photo (no GPS, no text); it will not be looked at again. */
    override suspend fun markSkipped(mediaId: Long, stage: IndexStage) = record(mediaId, stage, IndexStatus.SKIPPED, attempts = 0)

    /** Counts a failure; after [MAX_ATTEMPTS] the photo is left alone instead of retried forever. */
    override suspend fun markFailed(mediaId: Long, stage: IndexStage) {
        val attempts = (dao.attempts(mediaId, stage.name) ?: 0) + 1
        record(mediaId, stage, IndexStatus.FAILED, attempts)
    }

    suspend fun saveGeo(geo: MediaGeoEntity) = dao.upsertGeo(geo)

    suspend fun geo(mediaId: Long): MediaGeoEntity? = dao.geo(mediaId)

    /** Stores the text found in a photo so Search can find it. */
    suspend fun saveText(mediaId: Long, text: String) = dao.setOcr(mediaId, text)

    suspend fun text(mediaId: Long): String? = dao.ocr(mediaId)?.takeIf { it.isNotBlank() }

    private suspend fun record(mediaId: Long, stage: IndexStage, status: Int, attempts: Int) {
        dao.setState(IndexStateEntity(mediaId, stage.name, status, attempts, clock.nowMillis()))
    }

    companion object {
        /** A photo that fails this many times is skipped from then on (counted as finished). */
        const val MAX_ATTEMPTS = 3
    }
}
