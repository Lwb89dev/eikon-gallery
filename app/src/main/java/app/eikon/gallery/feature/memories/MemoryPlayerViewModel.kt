package app.eikon.gallery.feature.memories

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.memories.MemoryRepository
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.memories.MemoryId
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerState(
    val loading: Boolean = true,
    val id: MemoryId? = null,
    val photos: List<MediaItem> = emptyList(),
    val personName: String? = null,
)

@HiltViewModel
class MemoryPlayerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: MemoryRepository,
) : ViewModel() {
    private val id: MemoryId? = savedState.get<String>(ARG)?.let(MemoryId::parse)
    private val stateFlow = MutableStateFlow(PlayerState(loading = id != null, id = id))
    val state: StateFlow<PlayerState> = stateFlow.asStateFlow()

    init {
        viewModelScope.launch {
            if (id == null) {
                stateFlow.value = PlayerState(loading = false)
                return@launch
            }
            val open: MemoryId = id
            val photos = repository.keyPhotos(open, MAX_PHOTOS)
            stateFlow.value = PlayerState(false, open, photos, open.personId?.let { repository.personName(it) })
        }
    }

    fun hide() = launch { id?.let { repository.hide(it) } }

    fun showFewer() = launch { id?.let { repository.showFewer(it.kind) } }

    fun showLessOf(personId: Long) = launch { repository.showLessOf(personId) }

    fun excludeDay(day: LocalDate) = launch { repository.excludeDay(day) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    companion object {
        const val ARG = "memory"
        const val MAX_PHOTOS = 30
    }
}
