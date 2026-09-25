package app.eikon.gallery.feature.library

import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.eikon.gallery.data.AlbumRepository
import app.eikon.gallery.data.MediaRepository
import app.eikon.gallery.data.db.AlbumEntity
import app.eikon.gallery.data.db.AlbumSummary
import app.eikon.gallery.data.mediastore.MediaActions
import app.eikon.gallery.data.settings.AppSettings
import app.eikon.gallery.data.settings.SettingsRepository
import app.eikon.gallery.data.sync.LibrarySyncCoordinator
import app.eikon.gallery.data.sync.SyncStatus
import app.eikon.gallery.domain.GridSource
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.SortDirection
import app.eikon.gallery.domain.SortField
import app.eikon.gallery.domain.TimelineGrouping
import app.eikon.gallery.domain.TimelineLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One-shot things the screen must do (launch a system dialog, show a message). */
sealed interface LibraryEvent {
    data class LaunchSystemRequest(val sender: IntentSender) : LibraryEvent
    data class Share(val intent: Intent) : LibraryEvent
    data class MovedToTrash(val count: Int) : LibraryEvent
    data class AddedToAlbum(val albumName: String, val count: Int) : LibraryEvent
    data class Hidden(val count: Int) : LibraryEvent
    data class Unhidden(val count: Int) : LibraryEvent
    data class RemovedFromAlbum(val count: Int) : LibraryEvent
    data object ActionFailed : LibraryEvent
}

/** State of the album behind an album screen. */
sealed interface AlbumState {
    data object NotAnAlbum : AlbumState
    data object Loading : AlbumState
    data class Present(val album: AlbumEntity) : AlbumState

    /** The album was deleted (from this screen or elsewhere). */
    data object Gone : AlbumState
}

/** A change waiting for the user to answer the system confirmation dialog. */
private sealed interface PendingChange {
    val ids: List<Long>

    data class Favorite(override val ids: List<Long>, val favorite: Boolean) : PendingChange
    data class Trash(override val ids: List<Long>) : PendingChange
}

/**
 * One grid screen. The same class serves the main Library and every collection (album, preset,
 * folder, Hidden); [source] comes from the navigation route and decides which slice is shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MediaRepository,
    private val albumRepository: AlbumRepository,
    private val settingsRepository: SettingsRepository,
    private val actions: MediaActions,
    private val coordinator: LibrarySyncCoordinator,
) : ViewModel() {
    val source: GridSource = GridSource.parse(savedStateHandle.get<String>(SOURCE_ARG))

    val settings: StateFlow<AppSettings?> = settingsRepository.state

    private val loadedSettings = settingsRepository.state.filterNotNull()
    private val query = loadedSettings
        .map { source.toQuery(it.filters, it.sortField, it.direction) }
        .distinctUntilChanged()
    private val grouping = loadedSettings.map { TimelineGrouping.forColumns(it.gridColumns) }.distinctUntilChanged()

    /** Media of this screen's slice, paged from the Room index. Shared by grid and viewer. */
    val media: Flow<PagingData<MediaItem>> = query
        .flatMapLatest { repository.pagedMedia(it) }
        .cachedIn(viewModelScope)

    /** Date sections of the same query; null until the first load. */
    val timeline: StateFlow<TimelineLayout?> = combine(query, grouping) { q, g -> q to g }
        .flatMapLatest { (q, g) -> repository.timeline(q, g) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val syncStatus: StateFlow<SyncStatus> = coordinator.status

    /** Only meaningful when [source] is an album. */
    val album: StateFlow<AlbumState> = run {
        val id = (source as? GridSource.Album)?.id ?: return@run MutableStateFlow(AlbumState.NotAnAlbum)
        albumRepository.album(id)
            .map { if (it == null) AlbumState.Gone else AlbumState.Present(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlbumState.Loading)
    }

    /** Albums a selection can be added to (only observed while the picker is open). */
    val albumChoices: StateFlow<List<AlbumSummary>> = albumRepository.albumSummaries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val selectedItems = MutableStateFlow<Map<Long, MediaItem>>(emptyMap())
    val selection: StateFlow<Map<Long, MediaItem>> = selectedItems.asStateFlow()

    private val eventChannel = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = eventChannel.receiveAsFlow()

    private var pendingChange: PendingChange? = null
    private var dragBase: Map<Long, MediaItem> = emptyMap()

    // --- View settings ------------------------------------------------------------------------

    fun setColumns(columns: Int) {
        viewModelScope.launch { settingsRepository.setGridColumns(columns) }
    }

    fun setFilters(filters: LibraryFilters) {
        clearSelection()
        viewModelScope.launch { settingsRepository.setFilters(filters) }
    }

    fun setSort(field: SortField, direction: SortDirection) {
        viewModelScope.launch { settingsRepository.setSort(field, direction) }
    }

    fun retrySync() = coordinator.requestSync(force = true)

    // --- Selection ----------------------------------------------------------------------------

    fun toggleSelection(item: MediaItem) {
        selectedItems.update { current ->
            if (item.id in current) current - item.id else current + (item.id to item)
        }
    }

    fun clearSelection() {
        selectedItems.value = emptyMap()
    }

    /** A drag-select starts from whatever is selected now; the dragged range is added on top of it. */
    fun beginDragSelection() {
        dragBase = selectedItems.value
    }

    fun dragSelection(range: Collection<MediaItem>) {
        selectedItems.value = dragBase + range.associateBy { it.id }
    }

    // --- Actions on media ---------------------------------------------------------------------

    fun share(items: List<MediaItem>) {
        if (items.isEmpty()) return
        sendEvent(LibraryEvent.Share(actions.shareIntent(items)))
    }

    /** Favorites all given items, or un-favorites them when every one already is. */
    fun toggleFavorite(items: Collection<MediaItem>) {
        if (items.isEmpty()) return
        val favorite = items.any { !it.isFavorite }
        val ids = items.map { it.id }
        request(PendingChange.Favorite(ids, favorite)) { actions.favoriteRequest(items, favorite) }
    }

    fun trash(items: Collection<MediaItem>) {
        if (items.isEmpty()) return
        request(PendingChange.Trash(items.map { it.id })) { actions.trashRequest(items) }
    }

    /** Called with the outcome of the system confirmation dialog. */
    fun onSystemRequestFinished(approved: Boolean) {
        val change = pendingChange ?: return
        pendingChange = null
        if (!approved) return
        viewModelScope.launch { apply(change) }
    }

    // --- Albums and hiding (eikon's own data: no system dialog needed) ------------------------

    fun addToAlbum(albumId: Long, albumName: String, items: Collection<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            albumRepository.addToAlbum(albumId, items.map { it.id })
            eventChannel.send(LibraryEvent.AddedToAlbum(albumName, items.size))
            clearSelection()
        }
    }

    fun createAlbumAndAdd(name: String, items: Collection<MediaItem>) {
        viewModelScope.launch {
            val id = albumRepository.create(name) ?: return@launch eventChannel.send(LibraryEvent.ActionFailed)
            albumRepository.addToAlbum(id, items.map { it.id })
            eventChannel.send(LibraryEvent.AddedToAlbum(name.trim(), items.size))
            clearSelection()
        }
    }

    fun removeFromAlbum(items: Collection<MediaItem>) {
        val albumId = (source as? GridSource.Album)?.id ?: return
        viewModelScope.launch {
            albumRepository.removeFromAlbum(albumId, items.map { it.id })
            eventChannel.send(LibraryEvent.RemovedFromAlbum(items.size))
            clearSelection()
        }
    }

    fun renameAlbum(name: String) {
        val albumId = (source as? GridSource.Album)?.id ?: return
        viewModelScope.launch {
            if (!albumRepository.rename(albumId, name)) eventChannel.send(LibraryEvent.ActionFailed)
        }
    }

    fun deleteAlbum() {
        val albumId = (source as? GridSource.Album)?.id ?: return
        viewModelScope.launch { albumRepository.delete(albumId) }
    }

    fun hide(items: Collection<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            albumRepository.hide(items.map { it.id })
            eventChannel.send(LibraryEvent.Hidden(items.size))
            clearSelection()
        }
    }

    fun unhide(items: Collection<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            albumRepository.unhide(items.map { it.id })
            eventChannel.send(LibraryEvent.Unhidden(items.size))
            clearSelection()
        }
    }

    private suspend fun apply(change: PendingChange) {
        when (change) {
            is PendingChange.Favorite -> repository.applyFavorite(change.ids, change.favorite)
            is PendingChange.Trash -> {
                repository.removeFromIndex(change.ids)
                eventChannel.send(LibraryEvent.MovedToTrash(change.ids.size))
            }
        }
        clearSelection()
    }

    private fun request(change: PendingChange, buildRequest: () -> IntentSender) {
        val sender = try {
            buildRequest()
        } catch (_: IllegalArgumentException) {
            return sendEvent(LibraryEvent.ActionFailed)
        } catch (_: SecurityException) {
            return sendEvent(LibraryEvent.ActionFailed)
        }
        pendingChange = change
        sendEvent(LibraryEvent.LaunchSystemRequest(sender))
    }

    private fun sendEvent(event: LibraryEvent) {
        viewModelScope.launch { eventChannel.send(event) }
    }

    companion object {
        /** Navigation argument that carries the [GridSource] in its string form. */
        const val SOURCE_ARG = "source"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
