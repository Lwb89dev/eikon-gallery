package app.eikon.gallery.data.indexing

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.eikon.gallery.data.db.IndexStage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** What the settings screen shows: how far each step is and whether a run is in progress. */
data class AnalysisStatus(
    val places: StageProgress,
    val text: StageProgress,
    val semantic: StageProgress,
    val people: StageProgress,
    /** Photos fingerprinted for duplicates. */
    val duplicates: StageProgress,
    val running: Boolean,
    /** Steps that could not run because their model would not load. */
    val unavailable: Set<IndexStage> = emptySet(),
)

@Singleton
class AnalysisStatusRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val indexing: IndexingRepository,
    private val health: StageHealth,
) {
    /** Progress of each step, in the order places, text, what photos show, people, duplicates. */
    private val progress: Flow<Array<StageProgress>> = combine(
        indexing.progress(IndexStage.GEO),
        indexing.progress(IndexStage.OCR),
        indexing.progress(IndexStage.EMBED),
        indexing.progress(IndexStage.FACES),
        indexing.progress(IndexStage.PHASH),
    ) { it }

    val status: Flow<AnalysisStatus> = combine(progress, running(), health.unavailable) { steps, running, unavailable ->
        AnalysisStatus(steps[0], steps[1], steps[2], steps[3], steps[4], running, unavailable)
    }

    private fun running(): Flow<Boolean> {
        val manager = WorkManager.getInstance(context)
        return combine(
            manager.getWorkInfosForUniqueWorkFlow(IndexingScheduler.PERIODIC_WORK),
            manager.getWorkInfosForUniqueWorkFlow(IndexingScheduler.NOW_WORK),
        ) { periodic, now -> (periodic + now).any { it.state == WorkInfo.State.RUNNING } }
    }
}
