package app.eikon.gallery.feature.info

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.metadata.MediaDetailsReader
import app.eikon.gallery.domain.MediaDetails
import app.eikon.gallery.domain.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface InfoState {
    data object Loading : InfoState
    data class Loaded(val details: MediaDetails) : InfoState
    data object Failed : InfoState
}

@HiltViewModel
class InfoViewModel @Inject constructor(
    private val reader: MediaDetailsReader,
) : ViewModel() {
    private val mutableState = MutableStateFlow<InfoState>(InfoState.Loading)
    val state: StateFlow<InfoState> = mutableState.asStateFlow()
    private var job: Job? = null

    /** Reads [item]'s metadata; a newer call cancels an older one still in flight. */
    fun load(item: MediaItem) {
        job?.cancel()
        mutableState.value = InfoState.Loading
        job = viewModelScope.launch {
            mutableState.value = try {
                InfoState.Loaded(reader.read(item))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                InfoState.Failed
            }
        }
    }
}
