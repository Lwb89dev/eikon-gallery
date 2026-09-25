package app.eikon.gallery.feature.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.AlbumRepository
import app.eikon.gallery.data.MediaRepository
import app.eikon.gallery.data.db.AlbumSummary
import app.eikon.gallery.data.db.FolderSummary
import app.eikon.gallery.data.db.PersonSummary
import app.eikon.gallery.data.faces.PeopleRepository
import app.eikon.gallery.data.places.PlaceCover
import app.eikon.gallery.data.places.PlacesRepository
import app.eikon.gallery.data.mediastore.TrashRepository
import app.eikon.gallery.data.settings.AppSettings
import app.eikon.gallery.data.settings.SettingsRepository
import app.eikon.gallery.domain.GridSource
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.PresetKind
import app.eikon.gallery.domain.SortDirection
import app.eikon.gallery.domain.SortField
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Places found in the photos: how many countries (or unnamed group), and a photo to show. */
data class PlacesTile(val placeCount: Int, val cover: PlaceCover)

/** A ready-made collection with how many items it holds and its newest item as cover. */
data class PresetTile(val kind: PresetKind, val count: Int, val cover: MediaItem?)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    mediaRepository: MediaRepository,
    private val albumRepository: AlbumRepository,
    private val trashRepository: TrashRepository,
    settingsRepository: SettingsRepository,
    peopleRepository: PeopleRepository,
    placesRepository: PlacesRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings?> = settingsRepository.state

    /** Automatic collections; empty ones are dropped except Favorites, which is always offered. */
    val presets: StateFlow<List<PresetTile>> = combine(PresetKind.entries.map { kind -> presetFlow(mediaRepository, kind) }) { tiles ->
        tiles.filter { it.count > 0 || it.kind == PresetKind.FAVORITES }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val albums: StateFlow<List<AlbumSummary>> = albumRepository.albumSummaries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** People eikon has found, for the People tile. Hidden ones are not counted or shown. */
    val people: StateFlow<List<PersonSummary>> = peopleRepository.people
        .map { all -> all.filterNot { it.isHidden } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** The newest photo of the places found so far and how many places there are, for the Places tile. */
    val places: StateFlow<PlacesTile?> = placesRepository.tree
        .map { tree -> tree.takeIf { it.isNotEmpty() }?.let { PlacesTile(it.size, it.maxBy { node -> node.cover.takenAt }.cover) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val folders: StateFlow<List<FolderSummary>> = albumRepository.folders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val mutableTrashCount = MutableStateFlow<Int?>(null)

    /** Items in the system trash; unlike the rest this is read on demand, not observed. */
    val trashCount: StateFlow<Int?> = mutableTrashCount.asStateFlow()

    fun refreshTrashCount() {
        viewModelScope.launch { mutableTrashCount.value = runCatching { trashRepository.load().size }.getOrNull() }
    }

    fun createAlbum(name: String) {
        viewModelScope.launch { albumRepository.create(name) }
    }

    fun renameAlbum(id: Long, name: String) {
        viewModelScope.launch { albumRepository.rename(id, name) }
    }

    fun deleteAlbum(id: Long) {
        viewModelScope.launch { albumRepository.delete(id) }
    }

    fun moveAlbumEarlier(id: Long) {
        viewModelScope.launch { albumRepository.moveEarlier(id) }
    }

    fun moveAlbumLater(id: Long) {
        viewModelScope.launch { albumRepository.moveLater(id) }
    }

    private fun presetFlow(repository: MediaRepository, kind: PresetKind): Flow<PresetTile> {
        val query = GridSource.presetQuery(kind, SortField.DATE_TAKEN, SortDirection.NEWEST_FIRST)
        return combine(repository.count(query), repository.cover(query)) { count, cover -> PresetTile(kind, count, cover) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
