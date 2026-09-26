package app.eikon.gallery.feature.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.db.PersonSummary
import app.eikon.gallery.data.faces.PeopleRepository
import app.eikon.gallery.data.indexing.AnalysisStatusRepository
import app.eikon.gallery.data.indexing.StageProgress
import app.eikon.gallery.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the People screen shows. [people] leaves out hidden ones unless [showHidden]. */
data class PeopleState(
    val people: List<PersonSummary> = emptyList(),
    val hiddenCount: Int = 0,
    val showHidden: Boolean = false,
    /** Whether the user turned on finding people at all. */
    val enabled: Boolean = false,
    val progress: StageProgress? = null,
)

@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val repository: PeopleRepository,
    settings: SettingsRepository,
    status: AnalysisStatusRepository,
) : ViewModel() {
    private val showHidden = MutableStateFlow(false)

    val state: StateFlow<PeopleState> = combine(
        repository.people,
        showHidden,
        settings.state.map { it?.analysis?.people == true },
        status.status.map { it.people },
    ) { people, revealed, enabled, progress ->
        PeopleState(
            people = if (revealed) people else people.filterNot { it.isHidden },
            hiddenCount = people.count { it.isHidden },
            showHidden = revealed,
            enabled = enabled,
            progress = progress,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PeopleState())

    init {
        // People the user named alike from before naming one joined the two are one person; this is where that is put right.
        viewModelScope.launch { repository.mergeSameNames() }
    }

    fun toggleShowHidden() = showHidden.update { !it }

    fun rename(id: Long, name: String) {
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun setFavorite(id: Long, favorite: Boolean) {
        viewModelScope.launch { repository.setFavorite(id, favorite) }
    }

    fun setHidden(id: Long, hidden: Boolean) {
        viewModelScope.launch { repository.setHidden(id, hidden) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
