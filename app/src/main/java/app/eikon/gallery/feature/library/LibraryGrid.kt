package app.eikon.gallery.feature.library

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import app.eikon.gallery.R
import app.eikon.gallery.core.image.ThumbnailSizes
import app.eikon.gallery.data.settings.AppSettings
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.TimelineLabelFormatter
import app.eikon.gallery.domain.TimelineLayout
import app.eikon.gallery.domain.TimelineSection
import kotlinx.coroutines.delay

private val CellGap = 1.5.dp
private val AutoScrollEdge = 72.dp
private const val AUTO_SCROLL_MAX_PX_PER_FRAME = 32f
private const val FRAME_DELAY_MS = 16L
private const val SPRING_DAMPING = 0.85f
private const val SPRING_STIFFNESS = 300f
private const val HEADER_TYPE = "header"
private const val MEDIA_TYPE = "media"

/**
 * The library grid: date headers followed by square thumbnails.
 *
 * Only the section counts ([layout]) are known up front; the thumbnails come from the paged [items]
 * by media index, so a 100k-item library costs a few thousand small objects, not 100k. Gestures: tap
 * opens (or toggles in selection mode), long-press then drag selects a range, pinch changes density:
 * the grid follows the fingers continuously and settles on a column count when they lift (see [GridPinch]);
 * [onColumnsChange] is told the count it settled on.
 */
@Composable
fun LibraryGrid(
    layout: TimelineLayout,
    items: LazyPagingItems<MediaItem>,
    columns: Int,
    labels: TimelineLabelFormatter,
    selection: Map<Long, MediaItem>,
    state: LazyGridState,
    contentPadding: PaddingValues,
    onOpen: (mediaIndex: Int) -> Unit,
    onToggleSelect: (MediaItem) -> Unit,
    onColumnsChange: (Int) -> Unit,
    onBeginDragSelect: () -> Unit,
    onDragSelect: (List<MediaItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dragSelect = rememberDragSelectState(state, layout, items, onBeginDragSelect, onDragSelect)
    val pinch = rememberGridPinch(columns, onColumnsChange)
    val selectLabel = stringResource(R.string.action_select)
    val thumbnailSize = rememberThumbnailSize(pinch.columns)

    LazyVerticalGrid(
        columns = GridCells.Fixed(pinch.columns),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(CellGap),
        verticalArrangement = Arrangement.spacedBy(CellGap),
        userScrollEnabled = !dragSelect.active,
        modifier = modifier
            .pointerInput(pinch) { detectColumnPinch(pinch) }
            .pointerInput(dragSelect) { detectDragSelect(dragSelect) }
            .clipToBounds()
            .graphicsLayer { pinch.applyTo(this, size) },
    ) {
        timelineItems(layout, items, headerLabel = { labels.label(it.bucket, layout.grouping) }) { mediaIndex ->
            GridCell(mediaIndex, items, selection, dragSelect, selectLabel, thumbnailSize, onOpen, onToggleSelect)
        }
    }
}

/**
 * The size thumbnails are asked for at with [columns] columns: the smallest on the ladder (see [ThumbnailSizes]) that covers a cell. The width of the window stands in for the
 * width of the grid, which is the same on a phone.
 */
@Composable
private fun rememberThumbnailSize(columns: Int): IntSize {
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val gapPx = with(LocalDensity.current) { CellGap.roundToPx() }
    return remember(columns, windowWidth, gapPx) {
        val edge = ThumbnailSizes.forCell((windowWidth - gapPx * (columns - 1)) / columns)
        IntSize(edge, edge)
    }
}

/** One thumbnail of the grid, with what a tap and a long press do to it. */
@Composable
private fun GridCell(
    mediaIndex: Int,
    items: LazyPagingItems<MediaItem>,
    selection: Map<Long, MediaItem>,
    dragSelect: DragSelectState,
    selectLabel: String,
    thumbnailSize: IntSize,
    onOpen: (mediaIndex: Int) -> Unit,
    onToggleSelect: (MediaItem) -> Unit,
) {
    val item = if (mediaIndex < items.itemCount) items[mediaIndex] else null
    val selectionMode = selection.isNotEmpty()
    MediaCell(
        item = item,
        selected = item != null && item.id in selection,
        selectionMode = selectionMode,
        selectLabel = selectLabel,
        // Releasing a long press without moving also reports a click on the cell; the drag
        // session is still active at that moment, which is how the two are told apart.
        onClick = { if (item != null && !dragSelect.active) tap(item, mediaIndex, selectionMode, onToggleSelect, onOpen) },
        onLongClick = { if (item != null && !selectionMode) onToggleSelect(item) },
        thumbnailSize = thumbnailSize,
    )
}

/** A tap selects the photo while a selection is going on, and opens it otherwise. */
private fun tap(item: MediaItem, mediaIndex: Int, selectionMode: Boolean, onToggleSelect: (MediaItem) -> Unit, onOpen: (Int) -> Unit) {
    if (selectionMode) onToggleSelect(item) else onOpen(mediaIndex)
}

private fun LazyGridScope.timelineItems(
    layout: TimelineLayout,
    items: LazyPagingItems<MediaItem>,
    headerLabel: (TimelineSection) -> String,
    cell: @Composable (mediaIndex: Int) -> Unit,
) {
    layout.sections.forEach { section ->
        item(key = "h:${section.bucket}", span = { GridItemSpan(maxLineSpan) }, contentType = HEADER_TYPE) {
            SectionHeader(headerLabel(section))
        }
        items(
            count = section.count,
            key = { items.keyAt(section.startIndex + it) },
            contentType = { MEDIA_TYPE },
        ) { cell(section.startIndex + it) }
    }
}

/** Item id once loaded, a position-based key before that (peek never triggers a page load). */
private fun LazyPagingItems<MediaItem>.keyAt(index: Int): Any =
    (if (index < itemCount) peek(index)?.id else null) ?: "p$index"

// --- Pinch to change density -------------------------------------------------------------------

/**
 * The live state of a pinch on the grid. While two fingers are down the whole grid is scaled around the point between them (a layer transform, so it costs no layout and no
 * thumbnail load) and follows them continuously; whenever the cells have grown or shrunk by as much as one more or one fewer column would make them, [columns] moves to that
 * count and the scale is divided by the same factor, so the picture does not change at that moment (see [PinchMath]). When the fingers lift the grid settles on the nearer count
 * and the scale left over animates back to 1 with a spring.
 *
 * The column count lives here, not only in the saved settings, because the layout and the scale must change in the same frame: the settings arrive a moment later (and are
 * told only what the pinch settled on).
 */
@Stable
private class GridPinch(initialColumns: Int, private val onSettled: (Int) -> Unit) {
    var columns by mutableIntStateOf(initialColumns)
        private set
    var scale by mutableFloatStateOf(1f)
        private set

    /** Bumped whenever a gesture begins or ends: the springing back starts from the end and is cancelled by the beginning. */
    var generation by mutableIntStateOf(0)
        private set
    private var pivot = Offset.Zero
    private var fingersDown = false

    fun begin(at: Offset) {
        fingersDown = true
        pivot = at
        generation++
    }

    fun zoom(factor: Float) {
        val next = PinchMath.zoom(PinchState(columns, scale), factor, AppSettings.MIN_COLUMNS, AppSettings.MAX_COLUMNS)
        columns = next.columns
        scale = next.scale
    }

    fun release() {
        val next = PinchMath.settle(PinchState(columns, scale), AppSettings.MIN_COLUMNS, AppSettings.MAX_COLUMNS)
        columns = next.columns
        scale = next.scale
        fingersDown = false
        onSettled(columns)
        generation++
    }

    /** Called with the frames of the spring back to 1. */
    fun springTo(value: Float) {
        scale = value
    }

    val isSpringing: Boolean get() = !fingersDown && scale != 1f

    /** A count that came from outside (the saved settings) and is not the one the grid already has, when no pinch is going on. */
    fun adopt(saved: Int) {
        if (!fingersDown && saved != columns) columns = saved
    }

    fun applyTo(layer: GraphicsLayerScope, size: Size) {
        val s = scale
        layer.scaleX = s
        layer.scaleY = s
        layer.transformOrigin = TransformOrigin(pivot.x / size.width.coerceAtLeast(1f), pivot.y / size.height.coerceAtLeast(1f))
    }
}

@Composable
private fun rememberGridPinch(columns: Int, onSettled: (Int) -> Unit): GridPinch {
    val currentSettled by rememberUpdatedState(onSettled)
    val pinch = remember { GridPinch(columns) { currentSettled(it) } }
    LaunchedEffect(columns) { pinch.adopt(columns) }
    LaunchedEffect(pinch.generation) {
        if (pinch.isSpringing) animate(pinch.scale, 1f, animationSpec = spring(dampingRatio = SPRING_DAMPING, stiffness = SPRING_STIFFNESS)) { value, _ -> pinch.springTo(value) }
    }
    return pinch
}

/**
 * Two-finger pinch: hands the zoom of the fingers to [pinch] as they move. Runs on the Initial pass and consumes only multi-touch
 * events, so single-finger scrolling and taps are left to the grid untouched.
 */
private suspend fun PointerInputScope.detectColumnPinch(pinch: GridPinch) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var pinching = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            pinching = feedPinch(pinch, event, pinching)
        } while (event.changes.any { it.pressed })
        if (pinching) pinch.release()
    }
}

/** Passes one event to [pinch] if it has two fingers or more; returns whether a pinch is (now) going on. */
private fun feedPinch(pinch: GridPinch, event: PointerEvent, pinching: Boolean): Boolean {
    if (event.changes.count { it.pressed } < 2) return pinching
    if (!pinching) pinch.begin(event.calculateCentroid(useCurrent = true))
    pinch.zoom(event.calculateZoom())
    event.changes.forEach { it.consume() }
    return true
}

// --- Drag to select a range --------------------------------------------------------------------

/** State of one long-press-and-drag selection gesture. */
private class DragSelectState(
    private val state: LazyGridState,
    private val layout: () -> TimelineLayout,
    private val items: LazyPagingItems<MediaItem>,
    private val onBegin: () -> Unit,
    private val onSelect: (List<MediaItem>) -> Unit,
) {
    var active by mutableStateOf(false)
    var pointer by mutableStateOf(Offset.Zero)
    private var anchor = 0

    fun start(offset: Offset) {
        val index = mediaIndexAt(offset) ?: return
        active = true
        pointer = offset
        anchor = index
        onBegin()
        selectTo(index)
    }

    fun move(offset: Offset) {
        pointer = offset
        reselect()
    }

    /** Re-applies the selection for the current pointer, e.g. after auto-scrolling moved the items. */
    fun reselect() {
        mediaIndexAt(pointer)?.let(::selectTo)
    }

    fun end() {
        active = false
    }

    private fun selectTo(index: Int) {
        val range = minOf(anchor, index)..maxOf(anchor, index)
        onSelect(range.mapNotNull { if (it < items.itemCount) items.peek(it) else null })
    }

    private fun mediaIndexAt(offset: Offset): Int? {
        val hit = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            offset.x >= info.offset.x && offset.x < info.offset.x + info.size.width &&
                offset.y >= info.offset.y && offset.y < info.offset.y + info.size.height
        }
        return hit?.let { layout().mediaIndexOfGridPosition(it.index) }
    }
}

@Composable
private fun rememberDragSelectState(
    state: LazyGridState,
    layout: TimelineLayout,
    items: LazyPagingItems<MediaItem>,
    onBegin: () -> Unit,
    onSelect: (List<MediaItem>) -> Unit,
): DragSelectState {
    val currentLayout by rememberUpdatedState(layout)
    val currentBegin by rememberUpdatedState(onBegin)
    val currentSelect by rememberUpdatedState(onSelect)
    val drag = remember(state, items) {
        DragSelectState(state, { currentLayout }, items, { currentBegin() }, { currentSelect(it) })
    }
    val edgePx = with(LocalDensity.current) { AutoScrollEdge.toPx() }
    LaunchedEffect(drag.active) {
        while (drag.active) {
            scrollNearEdge(drag, state, edgePx)
            delay(FRAME_DELAY_MS)
        }
    }
    return drag
}

/** While the finger of a drag selection is near the top or bottom edge, scrolls the grid a little and applies the selection to what came under the finger. */
private suspend fun scrollNearEdge(drag: DragSelectState, state: LazyGridState, edgePx: Float) {
    val delta = edgeScrollDelta(drag.pointer.y, state.layoutInfo.viewportSize.height.toFloat(), edgePx)
    if (delta == 0f) return
    state.scrollBy(delta)
    drag.reselect()
}

private suspend fun PointerInputScope.detectDragSelect(drag: DragSelectState) {
    detectDragGesturesAfterLongPress(
        onDragStart = drag::start,
        onDrag = { change, _ ->
            change.consume()
            drag.move(change.position)
        },
        onDragEnd = drag::end,
        onDragCancel = drag::end,
    )
}

/** Pixels to scroll this frame while the finger is near the top or bottom edge; 0 in the middle. */
private fun edgeScrollDelta(pointerY: Float, viewportHeight: Float, edge: Float): Float = when {
    pointerY < edge -> -AUTO_SCROLL_MAX_PX_PER_FRAME * ((edge - pointerY) / edge).coerceIn(0f, 1f)
    pointerY > viewportHeight - edge -> AUTO_SCROLL_MAX_PX_PER_FRAME * ((pointerY - (viewportHeight - edge)) / edge).coerceIn(0f, 1f)
    else -> 0f
}
