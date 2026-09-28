package app.eikon.gallery.feature.edit

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.edit.EditClipboard
import app.eikon.gallery.data.edit.EditRepository
import app.eikon.gallery.data.edit.VideoEditExporter
import app.eikon.gallery.data.edit.VideoFrameLoader
import app.eikon.gallery.data.edit.toBitmap
import app.eikon.gallery.data.edit.toRgbImage
import app.eikon.gallery.data.sync.LibrarySyncCoordinator
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.edit.Adjustments
import app.eikon.gallery.domain.edit.Crop
import app.eikon.gallery.domain.edit.CropShape
import app.eikon.gallery.domain.edit.CropTool
import app.eikon.gallery.domain.edit.EditFilter
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.EditRenderer
import app.eikon.gallery.domain.edit.Geometry
import app.eikon.gallery.domain.edit.ImagePixelSource
import app.eikon.gallery.domain.edit.VideoTrim
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VideoEditUiState(
    val loading: Boolean = true,
    val item: MediaItem? = null,
    val recipe: EditRecipe = EditRecipe.NONE,
    /** The edit differs from what is stored, so leaving needs a confirmation. */
    val changed: Boolean = false,
    val tool: EditTool = EditTool.LIGHT,
    /** Of the file itself, not of the trim: what the trim bar's ends can move between. 0 until it is loaded. */
    val durationMs: Long = 0L,
    val filterThumbnails: Map<EditFilter, Bitmap> = emptyMap(),
    val canPaste: Boolean = false,
    val cropShape: CropShape = CropShape.FREE,
    /** 0..1 while a copy is being exported. */
    val saving: Float? = null,
)

sealed interface VideoEditEvent {
    data object Done : VideoEditEvent
    data object CopySaved : VideoEditEvent
    data object Failed : VideoEditEvent
}

/**
 * The video editor. It holds the recipe being edited; the live preview (Media3 effects and clipping) is built from it in
 * `VideoEditScreen`/`VideoPage`, not drawn here, since nothing here needs a GL context to know what the recipe *is*. "Done" stores the
 * recipe (as the same text a photo's edit is); leaving without it stores nothing. The original file is only ever read.
 */
@HiltViewModel
class VideoEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: EditRepository,
    private val frames: VideoFrameLoader,
    private val exporter: VideoEditExporter,
    private val clipboard: EditClipboard,
    private val sync: LibrarySyncCoordinator,
) : ViewModel(), EditToolsActions {
    private val mediaId: Long = savedState.get<Long>(EditViewModel.ARG) ?: -1L
    private val ui = MutableStateFlow(VideoEditUiState())
    val state: StateFlow<VideoEditUiState> = ui.asStateFlow()

    /** What is stored for this video now; [VideoEditUiState.changed] compares against it. */
    private var stored = EditRecipe.NONE

    private val eventChannel = Channel<VideoEditEvent>(Channel.BUFFERED)
    val events: Flow<VideoEditEvent> = eventChannel.receiveAsFlow()

    /** No suggestion to make from a video's own frames yet, unlike a photo's "Auto enhance". */
    override val autoEnhance: (() -> Unit)? = null

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { clipboard.recipe.collect { copied -> ui.update { it.copy(canPaste = copied != null) } } }
    }

    private suspend fun load() {
        val loaded = repository.mediaItem(mediaId) ?: return eventChannel.send(VideoEditEvent.Failed)
        stored = repository.recipe(mediaId) ?: EditRecipe.NONE
        val thumbnails = withContext(Dispatchers.Default) { thumbnailsOf(loaded.durationMs) }
        ui.update { it.copy(loading = false, item = loaded, recipe = stored, changed = false, durationMs = loaded.durationMs, filterThumbnails = thumbnails) }
    }

    /** One representative frame, run through the same renderer a photo's filter thumbnails use: nothing here needs the GPU pipeline. */
    private suspend fun thumbnailsOf(durationMs: Long): Map<EditFilter, Bitmap> {
        val frame = frames.frameAt(mediaId, durationMs / 2) ?: return emptyMap()
        try {
            val small = EditRenderer.render(ImagePixelSource(frame.toRgbImage()), EditRecipe.NONE, THUMBNAIL_EDGE)
            return EditFilter.entries.associateWith { filter -> EditRenderer.render(ImagePixelSource(small), EditRecipe(filter = filter), THUMBNAIL_EDGE).toBitmap() }
        } finally {
            frame.recycle()
        }
    }

    // --- Editing ------------------------------------------------------------------------------------

    fun selectTool(next: EditTool) = ui.update { it.copy(tool = next) }

    private fun edit(change: (EditRecipe) -> EditRecipe) = ui.update { s ->
        val next = change(s.recipe).clamped()
        s.copy(recipe = next, changed = next != stored)
    }

    override fun adjust(change: (Adjustments) -> Adjustments) = edit { it.copy(adjustments = change(it.adjustments)) }

    override fun setFilter(filter: EditFilter) = edit { it.copy(filter = filter, filterAmount = if (filter == it.filter) it.filterAmount else 1f) }

    override fun setFilterAmount(amount: Float) = edit { it.copy(filterAmount = amount) }

    override fun geometry(change: (Geometry) -> Geometry) = edit { it.copy(geometry = change(it.geometry)) }

    override fun rotate() = geometry { it.copy(quarterTurns = it.quarterTurns + 1, crop = Crop.FULL) }

    override fun flip() = geometry { it.copy(flipHorizontal = !it.flipHorizontal) }

    override fun setCropShape(shape: CropShape) {
        ui.update { it.copy(cropShape = shape) }
        val item = ui.value.item ?: return
        if (item.width <= 0 || item.height <= 0) return
        val aspect = item.width.toFloat() / item.height
        val ratio = CropTool.ratioOf(shape, aspect) ?: return
        geometry { it.copy(crop = CropTool.fit(it.crop, ratio, aspect)) }
    }

    /** Moves the trim, clamped to the file's own length. */
    fun setTrim(startMs: Long, endMs: Long) {
        val duration = ui.value.durationMs
        edit { it.copy(trim = VideoTrim(startMs.coerceIn(0L, duration), endMs.coerceIn(startMs, duration))) }
    }

    fun revert() = edit { EditRecipe.NONE }

    // --- Leaving ------------------------------------------------------------------------------------

    fun done() {
        val video = ui.value.item ?: return
        viewModelScope.launch {
            repository.save(video, ui.value.recipe)
            eventChannel.send(VideoEditEvent.Done)
        }
    }

    /** Renders the edit (color, the geometry it supports, and the trim) into a new video file next to the original, which is left as it is. */
    fun saveCopy() {
        val video = ui.value.item ?: return
        if (ui.value.saving != null) return
        viewModelScope.launch {
            ui.update { it.copy(saving = 0f) }
            try {
                exporter.saveCopy(video, ui.value.recipe) { progress -> ui.update { it.copy(saving = progress) } }
                sync.requestSync(force = true)
                eventChannel.send(VideoEditEvent.CopySaved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                eventChannel.send(VideoEditEvent.Failed)
            } finally {
                ui.update { it.copy(saving = null) }
            }
        }
    }

    // --- Copy and paste (the look only: adjustments and filter, never the crop, turns or trim) --------

    fun copyEdits() {
        viewModelScope.launch { clipboard.copy(ui.value.recipe) }
    }

    fun pasteEdits() {
        viewModelScope.launch {
            val copied = clipboard.recipe.first() ?: return@launch
            edit { it.copy(adjustments = copied.adjustments, filter = copied.filter, filterAmount = copied.filterAmount) }
        }
    }

    private companion object {
        const val THUMBNAIL_EDGE = 140
    }
}
