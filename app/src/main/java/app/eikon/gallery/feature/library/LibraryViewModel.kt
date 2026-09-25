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
import app.eikon.gallery.data.db.PersonEntity
import app.eikon.gallery.data.db.PersonSummary
import app.eikon.gallery.data.edit.EditClipboard
import app.eikon.gallery.data.edit.EditRepository
import app.eikon.gallery.data.indexing.AnalysisStatus
import app.eikon.gallery.data.embedding.PetClassifier
import app.eikon.gallery.data.places.PlacesRepository
import app.eikon.gallery.data.embedding.SemanticSearchService
import app.eikon.gallery.data.indexing.AnalysisStatusRepository
import app.eikon.gallery.data.mediastore.MediaActions
import app.eikon.gallery.data.places.GazetteerProvider
import app.eikon.gallery.data.settings.AppSettings
import app.eikon.gallery.data.settings.SettingsRepository
import app.eikon.gallery.data.sync.LibrarySyncCoordinator
import app.eikon.gallery.data.sync.SyncStatus
import app.eikon.gallery.domain.GridSource
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.data.faces.PeopleRepository
import app.eikon.gallery.domain.search.PeopleNames
import app.eikon.gallery.domain.search.SearchQueryParser
import app.eikon.gallery.domain.search.SearchSpec
import app.eikon.gallery.domain.SortDirection
import app.eikon.gallery.domain.SortField
import app.eikon.gallery.domain.TimelineGrouping
import app.eikon.gallery.domain.TimelineLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
    data class MovedToNewPerson(val count: Int) : LibraryEvent
    data class RemovedFromPeople(val count: Int) : LibraryEvent
    data class EditsPasted(val count: Int) : LibraryEvent
    data class EditsReverted(val count: Int) : LibraryEvent
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

/** State of the person behind a person screen. */
sealed interface PersonState {
    data object NotAPerson : PersonState
    data object Loading : PersonState
    data class Present(val person: PersonEntity) : PersonState

    /** The person no longer exists (merged into someone else, or emptied). */
    data object Gone : PersonState
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
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MediaRepository,
    private val albumRepository: AlbumRepository,
    private val settingsRepository: SettingsRepository,
    private val actions: MediaActions,
    private val coordinator: LibrarySyncCoordinator,
    private val gazetteers: GazetteerProvider,
    private val semanticSearch: SemanticSearchService,
    private val peopleRepository: PeopleRepository,
    private val petClassifier: PetClassifier,
    private val placesRepository: PlacesRepository,
    private val editRepository: EditRepository,
    private val editClipboard: EditClipboard,
    analysisStatusRepository: AnalysisStatusRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val source: GridSource = GridSource.parse(savedStateHandle.get<String>(SOURCE_ARG))

    val settings: StateFlow<AppSettings?> = settingsRepository.state

    private val loadedSettings = settingsRepository.state.filterNotNull()

    // --- Search text (only used when this screen is the Search tab) ---------------------------

    private val searchInput = MutableStateFlow(savedState.get<String>(SEARCH_TEXT_KEY).orEmpty())
    val searchText: StateFlow<String> = searchInput.asStateFlow()

    fun setSearchText(text: String) {
        searchInput.value = text
        savedState[SEARCH_TEXT_KEY] = text
    }

    /** What the typed text was understood as; shown to the user so the interpretation is never a mystery. */
    val searchSpec: StateFlow<SearchSpec> = searchInput
        .debounce(SEARCH_DEBOUNCE_MS)
        .distinctUntilChanged()
        .mapLatest { text -> parser().parse(text) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SearchSpec())

    private suspend fun parser() = SearchQueryParser(
        ZoneId.systemDefault(),
        { LocalDate.now() },
        gazetteers.get(),
        PeopleNames(peopleRepository.namedPeople().map { it.id to it.name }),
    )

    /**
     * [searchSpec] plus, when the user turned on analysis of what photos show, the photos that matched the
     * words by their content. Recomputed when the spec or that setting changes.
     */
    private val resolvedSpec: Flow<SearchSpec> = combine(searchSpec, loadedSettings.map { it.analysis.semantic }.distinctUntilChanged()) { spec, _ -> spec }
        .mapLatest { spec -> semanticSearch.prepare(spec) }

    /** The name of a place or area, looked up when this screen is one; null while it is being looked up. */
    val sourceTitle: StateFlow<SourceTitle?> = flow { emit(resolveTitle()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private suspend fun resolveTitle(): SourceTitle? = when (val current = source) {
        is GridSource.Place -> placesRepository.title(current.scope)?.let { SourceTitle.Text(it) } ?: SourceTitle.OtherPlaces
        is GridSource.Area -> {
            val area = current.scope
            val near = placesRepository.nearestCityName((area.minLatitude + area.maxLatitude) / 2, (area.minLongitude + area.maxLongitude) / 2)
            near?.let { SourceTitle.NearCity(it) } ?: SourceTitle.OtherPlaces
        }
        is GridSource.Memory -> SourceTitle.OfMemory(current.id, current.id.personId?.let { id -> peopleRepository.namedPeople().firstOrNull { it.id == id }?.name })
        else -> null
    }

    // --- Pets (only when this screen is the dogs or cats collection) -------------------------------

    /** The query id under which the photos of the pet were stored; null while searching or if there are none. */
    private val petHits = MutableStateFlow<Long?>(null)
    private val petsSearching = MutableStateFlow(source is GridSource.Pets)

    /** True while the pets are being looked for: the first time this takes a moment (the text model has to load). */
    val searchingPets: StateFlow<Boolean> = petsSearching.asStateFlow()

    init {
        val pets = source as? GridSource.Pets
        if (pets != null) {
            viewModelScope.launch {
                petHits.value = petClassifier.find(pets.kind)
                petsSearching.value = false
            }
        }
    }

    /** The query to show, or null when there is nothing to show yet (an empty search box). */
    private val query: Flow<LibraryQuery?> = if (source is GridSource.Pets) {
        combine(loadedSettings, petHits) { settings, id ->
            id?.let { LibraryQuery(LibraryScope.Semantic(it), LibraryFilters.NONE, settings.sortField, settings.direction) }
        }.distinctUntilChanged()
    } else if (source == GridSource.Search) {
        combine(loadedSettings, resolvedSpec) { settings, spec ->
            if (spec.isEmpty) null else LibraryQuery(LibraryScope.Search(spec), LibraryFilters.NONE, settings.sortField, settings.direction)
        }.distinctUntilChanged()
    } else {
        loadedSettings.map { source.toQuery(it.filters, it.sortField, it.direction) }.distinctUntilChanged()
    }
    private val grouping = loadedSettings.map { TimelineGrouping.forColumns(it.gridColumns) }.distinctUntilChanged()

    /** Media of this screen's slice, paged from the Room index. Shared by grid and viewer. */
    val media: Flow<PagingData<MediaItem>> = query
        .flatMapLatest { q -> if (q == null) flowOf(PagingData.empty()) else repository.pagedMedia(q) }
        .cachedIn(viewModelScope)

    /** Date sections of the same query; null until the first load. */
    val timeline: StateFlow<TimelineLayout?> = combine(query, grouping) { q, g -> q to g }
        .flatMapLatest { (q, g) -> if (q == null) flowOf(TimelineLayout.Empty) else repository.timeline(q, g) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** How much of the library the background analysis has covered, for the "results may be incomplete" note. */
    val analysis: StateFlow<AnalysisStatus?> = analysisStatusRepository.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val syncStatus: StateFlow<SyncStatus> = coordinator.status

    /** Only meaningful when [source] is an album. */
    val album: StateFlow<AlbumState> = run {
        val id = (source as? GridSource.Album)?.id ?: return@run MutableStateFlow(AlbumState.NotAnAlbum)
        albumRepository.album(id)
            .map { if (it == null) AlbumState.Gone else AlbumState.Present(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AlbumState.Loading)
    }

    /** Only meaningful when [source] is a person. */
    val person: StateFlow<PersonState> = run {
        val id = (source as? GridSource.Person)?.id ?: return@run MutableStateFlow(PersonState.NotAPerson)
        peopleRepository.person(id)
            .map { if (it == null) PersonState.Gone else PersonState.Present(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PersonState.Loading)
    }

    /** People this one could be merged into (only observed while the picker is open). */
    val mergeChoices: StateFlow<List<PersonSummary>> = peopleRepository.people
        .map { all -> all.filter { it.id != (source as? GridSource.Person)?.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

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

    // --- People (only when this screen is a person) -------------------------------------------------

    private val personId: Long? get() = (source as? GridSource.Person)?.id

    fun renamePerson(name: String) {
        val id = personId ?: return
        viewModelScope.launch { peopleRepository.rename(id, name) }
    }

    fun setPersonFavorite(favorite: Boolean) {
        val id = personId ?: return
        viewModelScope.launch { peopleRepository.setFavorite(id, favorite) }
    }

    fun setPersonHidden(hidden: Boolean) {
        val id = personId ?: return
        viewModelScope.launch { peopleRepository.setHidden(id, hidden) }
    }

    /** Makes this person and [into] one; this screen then finds its person gone and closes. */
    fun mergePersonInto(into: Long) {
        val id = personId ?: return
        viewModelScope.launch { peopleRepository.merge(id, into) }
    }

    /** The selected photos are not this person: they move to a new person the user can name or merge elsewhere. */
    fun splitFromPerson(items: Collection<MediaItem>) {
        val id = personId ?: return
        if (items.isEmpty()) return
        viewModelScope.launch {
            peopleRepository.split(id, items.map { it.id })
            eventChannel.send(LibraryEvent.MovedToNewPerson(items.size))
            clearSelection()
        }
    }

    /** The faces in the selected photos are not faces, or should stay out of People. */
    fun ignoreFacesOfPerson(items: Collection<MediaItem>) {
        val id = personId ?: return
        if (items.isEmpty()) return
        viewModelScope.launch {
            peopleRepository.ignoreFaces(id, items.map { it.id })
            eventChannel.send(LibraryEvent.RemovedFromPeople(items.size))
            clearSelection()
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

    // --- Edits ----------------------------------------------------------------------------------------

    /** True while some edits are copied and waiting to be pasted. */
    val canPasteEdits: StateFlow<Boolean> = editClipboard.recipe
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /** The photos among [items] take the look that was copied in the editor; each keeps its own crop, turns and straightening. Videos are left alone. */
    fun pasteEdits(items: Collection<MediaItem>) {
        val photos = items.filterNot { it.isVideo }
        if (photos.isEmpty()) return
        viewModelScope.launch {
            val recipe = editClipboard.recipe.first() ?: return@launch eventChannel.send(LibraryEvent.ActionFailed)
            editRepository.paste(photos, recipe)
            eventChannel.send(LibraryEvent.EditsPasted(photos.size))
            clearSelection()
        }
    }

    /** The photos among [items] that have an edit go back to the original. Their files were never changed. */
    fun revertEdits(items: Collection<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val edited = editRepository.editedAmong(items.map { it.id })
            if (edited.isNotEmpty()) {
                editRepository.revert(edited)
                eventChannel.send(LibraryEvent.EditsReverted(edited.size))
            }
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
        private const val SEARCH_TEXT_KEY = "q"
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
