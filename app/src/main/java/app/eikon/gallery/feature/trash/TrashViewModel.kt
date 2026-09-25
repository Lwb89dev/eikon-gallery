package app.eikon.gallery.feature.trash

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.mediastore.TrashRepository
import app.eikon.gallery.data.mediastore.TrashedMedia
import app.eikon.gallery.domain.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface TrashUiState {
    data object Loading : TrashUiState
    data class Loaded(val items: List<TrashedMedia>) : TrashUiState
    data object Failed : TrashUiState
}

sealed interface TrashEvent {
    data class LaunchSystemRequest(val sender: IntentSender) : TrashEvent
    data class Restored(val count: Int) : TrashEvent
    data class Deleted(val count: Int) : TrashEvent
    data object ActionFailed : TrashEvent
}

private enum class TrashAction { RESTORE, DELETE_FOREVER }

/**
 * "Recently deleted": the system trash, read on demand. Restoring and deleting for good both go
 * through the platform's confirmation dialog, after which the list is reloaded.
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    private val repository: TrashRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow<TrashUiState>(TrashUiState.Loading)
    val state: StateFlow<TrashUiState> = mutableState.asStateFlow()

    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = selectedIds.asStateFlow()

    private val eventChannel = Channel<TrashEvent>(Channel.BUFFERED)
    val events: Flow<TrashEvent> = eventChannel.receiveAsFlow()

    private var pending: Pair<TrashAction, Int>? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.value = try {
                TrashUiState.Loaded(repository.load())
            } catch (_: SecurityException) {
                TrashUiState.Failed
            } catch (_: IllegalStateException) {
                TrashUiState.Failed
            }
        }
    }

    fun daysLeft(media: TrashedMedia): Int = repository.daysLeft(media)

    fun toggleSelection(id: Long) = selectedIds.update { if (id in it) it - id else it + id }

    fun clearSelection() {
        selectedIds.value = emptySet()
    }

    fun selectAll(items: List<TrashedMedia>) {
        selectedIds.value = items.map { it.item.id }.toSet()
    }

    fun restore(items: Collection<MediaItem>) = request(TrashAction.RESTORE, items) { repository.restoreRequest(items) }

    fun deleteForever(items: Collection<MediaItem>) =
        request(TrashAction.DELETE_FOREVER, items) { repository.deleteForeverRequest(items) }

    /** Outcome of the system dialog. */
    fun onSystemRequestFinished(approved: Boolean) {
        val (action, count) = pending ?: return
        pending = null
        if (!approved) return
        viewModelScope.launch {
            eventChannel.send(if (action == TrashAction.RESTORE) TrashEvent.Restored(count) else TrashEvent.Deleted(count))
        }
        clearSelection()
        load()
    }

    private fun request(action: TrashAction, items: Collection<MediaItem>, build: () -> IntentSender) {
        if (items.isEmpty()) return
        val sender = try {
            build()
        } catch (_: IllegalArgumentException) {
            return sendEvent(TrashEvent.ActionFailed)
        } catch (_: SecurityException) {
            return sendEvent(TrashEvent.ActionFailed)
        }
        pending = action to items.size
        sendEvent(TrashEvent.LaunchSystemRequest(sender))
    }

    private fun sendEvent(event: TrashEvent) {
        viewModelScope.launch { eventChannel.send(event) }
    }
}
