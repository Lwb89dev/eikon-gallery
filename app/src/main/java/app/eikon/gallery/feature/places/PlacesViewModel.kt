package app.eikon.gallery.feature.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.indexing.AnalysisStatusRepository
import app.eikon.gallery.data.indexing.StageProgress
import app.eikon.gallery.data.places.Gazetteer
import app.eikon.gallery.data.places.PlaceNode
import app.eikon.gallery.data.places.PlacesRepository
import app.eikon.gallery.data.settings.SettingsRepository
import app.eikon.gallery.domain.places.GeoPoints
import app.eikon.gallery.domain.places.WorldMap
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Places screen shows. */
data class PlacesState(
    val tree: List<PlaceNode> = emptyList(),
    /** Whether the user turned on finding places at all. */
    val enabled: Boolean = false,
    val progress: StageProgress? = null,
    val loaded: Boolean = false,
)

/** The pieces the map needs, loaded when the map tab is first opened. */
class MapData(val points: GeoPoints, val world: WorldMap, val gazetteer: Gazetteer)

@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val repository: PlacesRepository,
    settings: SettingsRepository,
    status: AnalysisStatusRepository,
) : ViewModel() {
    val state: StateFlow<PlacesState> = combine(
        repository.tree,
        settings.state.map { it?.analysis?.places == true },
        status.status.map { it.places },
    ) { tree, enabled, progress -> PlacesState(tree, enabled, progress, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlacesState())

    private val mapData = MutableStateFlow<MapData?>(null)
    val map: StateFlow<MapData?> = mapData.asStateFlow()

    /** Loads (or reloads, so newly analysed photos appear) the positions and the outlines for the map. */
    fun loadMap() {
        viewModelScope.launch {
            mapData.value = MapData(repository.points(), repository.worldMap(), repository.gazetteer())
        }
    }

    suspend fun nearestCity(latitude: Double, longitude: Double): String? = repository.nearestCityName(latitude, longitude)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
