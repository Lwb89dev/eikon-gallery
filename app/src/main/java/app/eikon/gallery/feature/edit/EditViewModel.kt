package app.eikon.gallery.feature.edit

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.data.edit.EditClipboard
import app.eikon.gallery.data.edit.EditExporter
import app.eikon.gallery.data.edit.EditImageLoader
import app.eikon.gallery.data.edit.EditRepository
import app.eikon.gallery.data.edit.toBitmap
import app.eikon.gallery.data.edit.toRgbImage
import app.eikon.gallery.data.embedding.RgbImage
import app.eikon.gallery.data.sync.LibrarySyncCoordinator
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.edit.Adjustments
import app.eikon.gallery.domain.edit.AutoEnhance
import app.eikon.gallery.domain.edit.Crop
import app.eikon.gallery.domain.edit.CropShape
import app.eikon.gallery.domain.edit.CropTool
import app.eikon.gallery.domain.edit.EditFilter
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.EditRenderer
import app.eikon.gallery.domain.edit.Geometry
import app.eikon.gallery.domain.edit.ImagePixelSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The groups of tools along the bottom of the editor. */
enum class EditTool { LIGHT, COLOR, DETAIL, FILTERS, CROP }

data class EditUiState(
    val loading: Boolean = true,
    val item: MediaItem? = null,
    val recipe: EditRecipe = EditRecipe.NONE,
    /** The edit differs from what is stored, so leaving needs a confirmation. */
    val changed: Boolean = false,
    val tool: EditTool = EditTool.LIGHT,
    val preview: Bitmap? = null,
    /** The photo without the edit, drawn once, shown while the preview is held down. */
    val original: Bitmap? = null,
    val filterThumbnails: Map<EditFilter, Bitmap> = emptyMap(),
    val canPaste: Boolean = false,
    /** What the crop is locked to while the crop tool is open. */
    val cropShape: CropShape = CropShape.FREE,
    /** 0..1 while a copy is being saved. */
    val saving: Float? = null,
)

sealed interface EditEvent {
    /** The edit was stored; leave the editor. */
    data object Done : EditEvent

    /** A copy was saved as a new photo. */
    data class CopySaved(val keptMetadata: Boolean) : EditEvent
    data object Failed : EditEvent
}

/**
 * The editor. It holds the recipe being edited and draws it, on a reduced copy of the photo, every time it changes; the original file is
 * only read (for the preview and, for "Save a copy", at full size) and never written. "Done" stores the recipe; leaving without it stores nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: EditRepository,
    private val loader: EditImageLoader,
    private val exporter: EditExporter,
    private val clipboard: EditClipboard,
    private val sync: LibrarySyncCoordinator,
) : ViewModel() {
    private val mediaId: Long = savedState.get<Long>(ARG) ?: -1L
    private val ui = MutableStateFlow(EditUiState())
    val state: StateFlow<EditUiState> = ui.asStateFlow()

    /** What is stored for this photo now; [EditUiState.changed] compares against it. */
    private var stored = EditRecipe.NONE
    private var source: RgbImage? = null

    private val eventChannel = Channel<EditEvent>(Channel.BUFFERED)
    val events: Flow<EditEvent> = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { clipboard.recipe.collect { copied -> ui.update { it.copy(canPaste = copied != null) } } }
        viewModelScope.launch {
            ui.map { shownRecipe(it.recipe, it.tool) to it.loading }.distinctUntilChanged()
                .mapLatest { (recipe, _) -> draw(recipe) }
                .collect { edited -> ui.update { it.copy(preview = edited ?: it.preview) } }
        }
    }

    private suspend fun load() {
        val loaded = repository.mediaItem(mediaId) ?: return eventChannel.send(EditEvent.Failed)
        stored = repository.recipe(mediaId) ?: EditRecipe.NONE
        val decoded = withContext(Dispatchers.Default) {
            val bitmap = try { loader.decode(mediaId, PREVIEW_EDGE) } catch (_: Exception) { null }
            bitmap?.toRgbImage().also { bitmap?.recycle() }
        } ?: return eventChannel.send(EditEvent.Failed)
        source = decoded
        val (thumbnails, plain) = withContext(Dispatchers.Default) { thumbnailsOf(decoded) to decoded.toBitmap() }
        ui.update { it.copy(loading = false, item = loaded, recipe = stored, changed = false, filterThumbnails = thumbnails, original = plain) }
    }

    /** While the crop tool is open the whole (turned, straightened) picture is shown so the crop can be drawn over it, and dragging the crop does not redraw it. */
    private fun shownRecipe(recipe: EditRecipe, tool: EditTool): EditRecipe =
        if (tool == EditTool.CROP) recipe.copy(geometry = recipe.geometry.copy(crop = Crop.FULL)) else recipe

    private suspend fun draw(recipe: EditRecipe): Bitmap? {
        val picture = source ?: return null
        return withContext(Dispatchers.Default) { EditRenderer.render(ImagePixelSource(picture), recipe, PREVIEW_EDGE).toBitmap() }
    }

    private fun thumbnailsOf(picture: RgbImage): Map<EditFilter, Bitmap> {
        val small = EditRenderer.render(ImagePixelSource(picture), EditRecipe.NONE, THUMBNAIL_EDGE)
        return EditFilter.entries.associateWith { filter -> EditRenderer.render(ImagePixelSource(small), EditRecipe(filter = filter), THUMBNAIL_EDGE).toBitmap() }
    }

    // --- Editing ------------------------------------------------------------------------------------

    fun selectTool(next: EditTool) = ui.update { it.copy(tool = next) }

    private fun edit(change: (EditRecipe) -> EditRecipe) = ui.update { s ->
        val next = change(s.recipe).clamped()
        s.copy(recipe = next, changed = next != stored)
    }

    fun adjust(change: (Adjustments) -> Adjustments) = edit { it.copy(adjustments = change(it.adjustments)) }

    fun setFilter(filter: EditFilter) = edit { it.copy(filter = filter, filterAmount = if (filter == it.filter) it.filterAmount else 1f) }

    fun setFilterAmount(amount: Float) = edit { it.copy(filterAmount = amount) }

    fun geometry(change: (Geometry) -> Geometry) = edit { it.copy(geometry = change(it.geometry)) }

    /** Locks the crop to [shape], fitting the largest crop of that shape into the picture. */
    fun setCropShape(shape: CropShape) {
        val picture = ui.value.preview
        ui.update { it.copy(cropShape = shape) }
        val aspect = picture?.let { it.width.toFloat() / it.height } ?: return
        val ratio = CropTool.ratioOf(shape, aspect) ?: return
        geometry { it.copy(crop = CropTool.fit(it.crop, ratio, aspect)) }
    }

    fun rotate() = geometry { it.copy(quarterTurns = it.quarterTurns + 1, crop = Crop.FULL) }

    fun flip() = geometry { it.copy(flipHorizontal = !it.flipHorizontal) }

    /** Sets the adjustments to what "Auto enhance" proposes for this photo; they are ordinary sliders afterwards. */
    fun autoEnhance() {
        val current = source ?: return
        viewModelScope.launch {
            val proposal = withContext(Dispatchers.Default) { AutoEnhance.suggest(current) }
            edit { it.copy(adjustments = proposal) }
        }
    }

    /** Back to the original picture: every adjustment, the filter and the geometry are cleared (stored only when "Done" is pressed). */
    fun revert() = edit { EditRecipe.NONE }

    // --- Leaving ------------------------------------------------------------------------------------

    /** Stores the edit (or, if it changes nothing, removes the stored one) and leaves. The file is never written. */
    fun done() {
        val photo = ui.value.item ?: return
        viewModelScope.launch {
            repository.save(photo, ui.value.recipe)
            eventChannel.send(EditEvent.Done)
        }
    }

    /** Saves the edit as a new photo next to the original, which is left as it is. */
    fun saveCopy() {
        val photo = ui.value.item ?: return
        if (ui.value.saving != null) return
        viewModelScope.launch {
            ui.update { it.copy(saving = 0f) }
            try {
                val copy = exporter.saveCopy(photo, ui.value.recipe) { progress -> ui.update { it.copy(saving = progress) } }
                sync.requestSync(force = true)
                eventChannel.send(EditEvent.CopySaved(copy.keptMetadata))
            } catch (_: Exception) {
                eventChannel.send(EditEvent.Failed)
            } catch (_: OutOfMemoryError) {
                // A very large photo on a phone short of memory: nothing was stored, and the message says the copy could not be made.
                eventChannel.send(EditEvent.Failed)
            } finally {
                ui.update { it.copy(saving = null) }
            }
        }
    }

    // --- Copy and paste ---------------------------------------------------------------------------

    fun copyEdits() {
        viewModelScope.launch { clipboard.copy(ui.value.recipe) }
    }

    /** Gives this photo the look last copied; its own crop and turns stay. */
    fun pasteEdits() {
        viewModelScope.launch {
            val copied = clipboard.recipe.first() ?: return@launch
            edit { it.copy(adjustments = copied.adjustments, filter = copied.filter, filterAmount = copied.filterAmount) }
        }
    }

    companion object {
        const val ARG = "id"
        private const val PREVIEW_EDGE = 1400
        private const val THUMBNAIL_EDGE = 140
    }
}
