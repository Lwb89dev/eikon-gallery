package app.eikon.gallery.feature.duplicates

import android.content.IntentSender
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.AlbumRepository
import app.eikon.gallery.data.MediaRepository
import app.eikon.gallery.data.duplicates.DuplicateEntry
import app.eikon.gallery.data.duplicates.DuplicateMode
import app.eikon.gallery.data.duplicates.DuplicatesRepository
import app.eikon.gallery.data.indexing.AnalysisStatusRepository
import app.eikon.gallery.data.indexing.StageProgress
import app.eikon.gallery.data.mediastore.MediaActions
import app.eikon.gallery.data.settings.SettingsRepository
import app.eikon.gallery.domain.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One group on screen, with the items the user has marked to move to the trash. */
data class GroupState(val entry: DuplicateEntry, val marked: Set<Long>)

data class DuplicatesState(
    val loading: Boolean = true,
    val groups: List<GroupState> = emptyList(),
    /** Whether the analysis this list depends on is switched on. */
    val enabled: Boolean = false,
    val progress: StageProgress? = null,
)

sealed interface DuplicatesEvent {
    data class LaunchSystemRequest(val sender: IntentSender) : DuplicatesEvent
    data class MovedToTrash(val count: Int) : DuplicatesEvent
    data object ActionFailed : DuplicatesEvent
}

/** A trash request waiting for the system's confirmation, with what to do once it is given. */
private class PendingTrash(
    val groupKey: String,
    val removed: List<MediaItem>,
    val keeper: MediaItem?,
    val albumIds: List<Long>,
    /** The copy that stays should become a favorite because one that goes was. */
    val inheritFavorite: Boolean,
)

@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: DuplicatesRepository,
    private val mediaRepository: MediaRepository,
    private val albums: AlbumRepository,
    private val actions: MediaActions,
    settings: SettingsRepository,
    status: AnalysisStatusRepository,
) : ViewModel() {
    val mode: DuplicateMode = DuplicateMode.entries.firstOrNull { it.name == savedState.get<String>(MODE_ARG) } ?: DuplicateMode.DUPLICATES

    private val groups = MutableStateFlow<List<GroupState>?>(null)
    private val enabled: Flow<Boolean> = settings.state.map { s -> if (mode == DuplicateMode.DUPLICATES) s?.analysis?.duplicates == true else s?.analysis?.semantic == true }
    private val progress: Flow<StageProgress> = status.status.map { if (mode == DuplicateMode.DUPLICATES) it.duplicates else it.semantic }

    private val stateFlow = MutableStateFlow(DuplicatesState())
    val state: StateFlow<DuplicatesState> = stateFlow.asStateFlow()

    private val eventChannel = Channel<DuplicatesEvent>(Channel.BUFFERED)
    val events: Flow<DuplicatesEvent> = eventChannel.receiveAsFlow()
    private var pending: PendingTrash? = null

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            enabled.collect { on -> stateFlow.update { it.copy(enabled = on) } }
        }
        viewModelScope.launch {
            progress.collect { p -> stateFlow.update { it.copy(progress = p) } }
        }
    }

    private suspend fun load() {
        val entries = if (mode == DuplicateMode.DUPLICATES) repository.duplicates() else repository.similar()
        val states = entries.map { GroupState(it, initialMarks(it)) }
        stateFlow.update { it.copy(loading = false, groups = states) }
    }

    /** Copies: everything but the suggested one is marked. Similar shots: nothing is, the user picks. */
    private fun initialMarks(entry: DuplicateEntry): Set<Long> =
        if (entry.bestId == null) emptySet() else entry.items.map { it.id }.filter { it != entry.bestId }.toSet()

    fun toggle(groupKey: String, id: Long) = changeGroup(groupKey) { group ->
        group.copy(marked = if (id in group.marked) group.marked - id else group.marked + id)
    }

    /** The user says this is not a group of copies (or similar shots); it will not be offered again. */
    fun dismiss(groupKey: String) {
        viewModelScope.launch {
            repository.dismiss(groupKey)
            stateFlow.update { s -> s.copy(groups = s.groups.filterNot { it.entry.key == groupKey }) }
        }
    }

    /** Asks the system to move the marked items to the trash; it shows its own confirmation, and nothing happens without it. */
    fun trashMarked(groupKey: String) {
        val group = stateFlow.value.groups.firstOrNull { it.entry.key == groupKey } ?: return
        val removed = group.entry.items.filter { it.id in group.marked }
        val kept = group.entry.items.filter { it.id !in group.marked }
        if (removed.isEmpty() || kept.isEmpty()) return eventFailed()
        viewModelScope.launch {
            val albumIds = repository.albumsOf(removed.map { it.id })
            val keeper = kept.first()
            pending = PendingTrash(groupKey, removed, keeper, albumIds, inheritFavorite = removed.any { it.isFavorite } && kept.none { it.isFavorite })
            try {
                eventChannel.send(DuplicatesEvent.LaunchSystemRequest(actions.trashRequest(removed)))
            } catch (_: IllegalArgumentException) {
                pending = null
                eventChannel.send(DuplicatesEvent.ActionFailed)
            } catch (_: SecurityException) {
                pending = null
                eventChannel.send(DuplicatesEvent.ActionFailed)
            }
        }
    }

    /** Called with the outcome of the system confirmation dialog. */
    fun onSystemRequestFinished(approved: Boolean) {
        val change = pending ?: return
        pending = null
        if (!approved) return
        viewModelScope.launch {
            mediaRepository.removeFromIndex(change.removed.map { it.id })
            change.keeper?.let { keeper -> change.albumIds.forEach { albums.addToAlbum(it, listOf(keeper.id)) } }
            stateFlow.update { s -> s.copy(groups = s.groups.filterNot { it.entry.key == change.groupKey }) }
            eventChannel.send(DuplicatesEvent.MovedToTrash(change.removed.size))
            if (change.inheritFavorite && change.keeper != null) requestFavorite(change.keeper)
        }
    }

    private suspend fun requestFavorite(keeper: MediaItem) {
        try {
            eventChannel.send(DuplicatesEvent.LaunchSystemRequest(actions.favoriteRequest(listOf(keeper), true)))
        } catch (_: IllegalArgumentException) {
            return
        } catch (_: SecurityException) {
            return
        }
    }

    private fun changeGroup(key: String, change: (GroupState) -> GroupState) {
        stateFlow.update { s -> s.copy(groups = s.groups.map { if (it.entry.key == key) change(it) else it }) }
    }

    private fun eventFailed() {
        viewModelScope.launch { eventChannel.send(DuplicatesEvent.ActionFailed) }
    }

    companion object {
        const val MODE_ARG = "mode"
    }
}
