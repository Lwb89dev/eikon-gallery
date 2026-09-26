package app.eikon.gallery.feature.info

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.db.MetadataOriginalEntity
import app.eikon.gallery.data.embedding.PhotoLabeler
import app.eikon.gallery.data.embedding.PhotoLabels
import app.eikon.gallery.data.mediastore.MediaActions
import app.eikon.gallery.data.metadata.IndexedInfo
import app.eikon.gallery.data.metadata.IndexedInfoReader
import app.eikon.gallery.data.metadata.MediaDetailsReader
import app.eikon.gallery.data.metadata.MetadataChange
import app.eikon.gallery.data.metadata.MetadataException
import app.eikon.gallery.data.metadata.MetadataRepository
import app.eikon.gallery.data.metadata.MetadataWriter
import app.eikon.gallery.data.places.City
import app.eikon.gallery.data.places.GazetteerProvider
import app.eikon.gallery.data.places.PlacesRepository
import app.eikon.gallery.data.sync.LibrarySyncCoordinator
import app.eikon.gallery.domain.EditableFormats
import app.eikon.gallery.domain.MediaDetails
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.places.WorldMap
import app.eikon.gallery.domain.search.TextNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What can be done to this photo's file, and what there is to undo. */
data class EditableMetadata(
    /** The format can be written and the photo-location permission is held (writing without it would destroy the location). */
    val canEdit: Boolean,
    /** The format can be written but the photo-location permission is missing, so the editing is offered with a way to grant it instead. */
    val needsLocationPermission: Boolean,
    val canRevertDate: Boolean,
    val canRevertLocation: Boolean,
    /** An earlier change was interrupted: its safety copy is still there and the file may need putting back. */
    val interrupted: Boolean,
)

sealed interface InfoState {
    data object Loading : InfoState
    data class Loaded(val details: MediaDetails, val indexed: IndexedInfo, val editable: EditableMetadata) : InfoState
    data object Failed : InfoState
}

/** Things the screen must do or say once, not remember. */
sealed interface InfoEvent {
    data class LaunchSystemRequest(val sender: IntentSender) : InfoEvent
    data object ChangeDone : InfoEvent
    data class ChangeFailed(val reason: MetadataException.Reason) : InfoEvent
}

@HiltViewModel
class InfoViewModel @Inject constructor(
    private val reader: MediaDetailsReader,
    private val indexedReader: IndexedInfoReader,
    private val metadata: MetadataRepository,
    private val writer: MetadataWriter,
    private val actions: MediaActions,
    private val sync: LibrarySyncCoordinator,
    private val labeler: PhotoLabeler,
    private val places: PlacesRepository,
    private val gazetteers: GazetteerProvider,
    private val access: app.eikon.gallery.core.permissions.MediaAccessChecker,
) : ViewModel() {
    private val mutableState = MutableStateFlow<InfoState>(InfoState.Loading)
    val state: StateFlow<InfoState> = mutableState.asStateFlow()

    private val mutableLabels = MutableStateFlow<PhotoLabels?>(null)

    /** What the photo probably shows, worked out from its stored vector after the rest is on screen (loading the text model takes a moment); null if unknown. */
    val labels: StateFlow<PhotoLabels?> = mutableLabels.asStateFlow()

    private val mutableWorld = MutableStateFlow<WorldMap?>(null)

    /** The country outlines for the small map of where a photo was taken. */
    val world: StateFlow<WorldMap?> = mutableWorld.asStateFlow()

    private val mutableCities = MutableStateFlow<List<City>>(emptyList())

    /** Cities matching what was typed in the location dialog. */
    val cities: StateFlow<List<City>> = mutableCities.asStateFlow()

    private val eventChannel = Channel<InfoEvent>(Channel.BUFFERED)
    val events: Flow<InfoEvent> = eventChannel.receiveAsFlow()

    private var job: Job? = null
    private var pending: Request? = null

    /** Reads [item]'s metadata; a newer call cancels an older one still in flight. */
    fun load(item: MediaItem) {
        job?.cancel()
        mutableState.value = InfoState.Loading
        job = viewModelScope.launch {
            mutableState.value = try {
                InfoState.Loaded(reader.read(item), indexedReader.read(item.id), editable(item))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                InfoState.Failed
            }
            mutableLabels.value = labeler.of(item.id)
            if (mutableWorld.value == null) mutableWorld.value = places.worldMap()
        }
    }

    private suspend fun editable(item: MediaItem): EditableMetadata {
        val format = !item.isVideo && EditableFormats.canWrite(item.mimeType)
        val permitted = access.canReadLocation()
        return EditableMetadata(
            canEdit = format && permitted,
            needsLocationPermission = format && !permitted,
            canRevertDate = metadata.hasOriginal(item.id, MetadataOriginalEntity.FIELD_DATE),
            canRevertLocation = metadata.hasOriginal(item.id, MetadataOriginalEntity.FIELD_LOCATION),
            interrupted = writer.isInterrupted(item),
        )
    }

    fun setCaption(item: MediaItem, text: String) {
        viewModelScope.launch {
            metadata.setCaption(item.id, text)
            load(item)
        }
    }

    /** Asks the system for permission to change the file, then makes [change] once it is given. Nothing is written before the answer. */
    fun change(item: MediaItem, change: MetadataChange) = ask(item, Request.Change(change))

    /** Puts the file back as it was before a change that was interrupted; asks the system first, like any change. */
    fun restoreInterrupted(item: MediaItem) = ask(item, Request.Restore)

    private fun ask(item: MediaItem, request: Request) {
        pending = request
        eventChannel.trySend(InfoEvent.LaunchSystemRequest(actions.writeRequest(item)))
    }

    /** The user answered the system's question about changing [item]'s file. */
    fun onSystemRequestFinished(item: MediaItem, granted: Boolean) {
        val request = pending.also { pending = null } ?: return
        if (!granted) return
        viewModelScope.launch {
            when (request) {
                is Request.Change -> apply(item) { writer.apply(item, request.change) }
                Request.Restore -> apply(item) { writer.restoreInterrupted(item) }
            }
        }
    }

    private sealed interface Request {
        data class Change(val change: MetadataChange) : Request
        data object Restore : Request
    }

    private suspend fun apply(item: MediaItem, action: suspend () -> Unit) {
        try {
            action()
            sync.requestSync(force = true)
            eventChannel.send(InfoEvent.ChangeDone)
        } catch (e: MetadataException) {
            eventChannel.send(InfoEvent.ChangeFailed(e.reason))
        }
        load(item)
    }

    fun searchCities(text: String) {
        viewModelScope.launch {
            val name = TextNormalizer.name(text)
            if (name.isEmpty()) {
                mutableCities.value = emptyList()
                return@launch
            }
            val gazetteer = gazetteers.get()
            val ids = gazetteer.match(name)?.cityIds.orEmpty()
            mutableCities.value = ids.mapNotNull { gazetteer.city(it) }.sortedByDescending { it.population }.take(MAX_CITIES)
        }
    }

    fun clearCities() {
        mutableCities.value = emptyList()
    }

    private companion object {
        const val MAX_CITIES = 6
    }
}
