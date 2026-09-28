package app.eikon.gallery.feature.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.edit.EditRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The "edit" destination is one nav route for both a photo and a video; this is the small lookup that decides, once, which of the two
 * editors to show, so [EditViewModel] and [VideoEditViewModel] can each stay built only for their own kind of file.
 */
@HiltViewModel
class EditRouterViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: EditRepository,
) : ViewModel() {
    private val mediaId: Long = savedState.get<Long>(EditViewModel.ARG) ?: -1L

    /** Null until the lookup returns; false if the id names nothing (the editor itself then reports the failure). */
    private val _isVideo = MutableStateFlow<Boolean?>(null)
    val isVideo: StateFlow<Boolean?> = _isVideo.asStateFlow()

    init {
        viewModelScope.launch { _isVideo.value = repository.mediaItem(mediaId)?.isVideo ?: false }
    }
}
