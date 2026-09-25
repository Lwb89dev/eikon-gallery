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
    val running: Boolean,
)

@Singleton
class AnalysisStatusRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val indexing: IndexingRepository,
) {
    val status: Flow<AnalysisStatus> = combine(
        indexing.progress(IndexStage.GEO),
        indexing.progress(IndexStage.OCR),
        running(),
    ) { places, text, running -> AnalysisStatus(places, text, running) }

    private fun running(): Flow<Boolean> {
        val manager = WorkManager.getInstance(context)
        return combine(
            manager.getWorkInfosForUniqueWorkFlow(IndexingScheduler.PERIODIC_WORK),
            manager.getWorkInfosForUniqueWorkFlow(IndexingScheduler.NOW_WORK),
        ) { periodic, now -> (periodic + now).any { it.state == WorkInfo.State.RUNNING } }
    }
}
