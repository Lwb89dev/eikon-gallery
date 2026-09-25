package app.eikon.gallery.feature.library

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import app.eikon.gallery.R
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.TimelineGrouping
import app.eikon.gallery.domain.TimelineLabelFormatter
import app.eikon.gallery.domain.TimelineLayout
import app.eikon.gallery.domain.TimelineSection
import kotlinx.coroutines.delay

private val CellGap = 1.5.dp
private val AutoScrollEdge = 72.dp
private const val AUTO_SCROLL_MAX_PX_PER_FRAME = 32f
private const val FRAME_DELAY_MS = 16L
private const val PINCH_OUT_THRESHOLD = 1.3f
private const val PINCH_IN_THRESHOLD = 0.77f
private const val HEADER_TYPE = "header"
private const val MEDIA_TYPE = "media"

/**
 * The library grid: date headers followed by square thumbnails.
 *
 * Only the section counts ([layout]) are known up front; the thumbnails come from the paged [items]
 * by media index, so a 100k-item library costs a few thousand small objects, not 100k. Gestures: tap
 * opens (or toggles in selection mode), long-press then drag selects a range, pinch changes density.
 */
@Composable
fun LibraryGrid(
    layout: TimelineLayout,
    items: LazyPagingItems<MediaItem>,
    columns: Int,
    grouping: TimelineGrouping,
    labels: TimelineLabelFormatter,
    selection: Map<Long, MediaItem>,
    state: LazyGridState,
    contentPadding: PaddingValues,
    onOpen: (mediaIndex: Int) -> Unit,
    onToggleSelect: (MediaItem) -> Unit,
    onColumnsStep: (delta: Int) -> Unit,
    onBeginDragSelect: () -> Unit,
    onDragSelect: (List<MediaItem>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dragSelect = rememberDragSelectState(state, layout, items, onBeginDragSelect, onDragSelect)
    val currentColumnsStep by rememberUpdatedState(onColumnsStep)
    val selectionMode = selection.isNotEmpty()
    val selectLabel = stringResource(R.string.action_select)

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(CellGap),
        verticalArrangement = Arrangement.spacedBy(CellGap),
        userScrollEnabled = !dragSelect.active,
        modifier = modifier
            .pointerInput(Unit) { detectColumnPinch { currentColumnsStep(it) } }
            .pointerInput(dragSelect) { detectDragSelect(dragSelect) },
    ) {
        timelineItems(layout, items, headerLabel = { labels.label(it.bucket, grouping) }) { mediaIndex ->
            val item = if (mediaIndex < items.itemCount) items[mediaIndex] else null
            MediaCell(
                item = item,
                selected = item != null && item.id in selection,
                selectionMode = selectionMode,
                selectLabel = selectLabel,
                // Releasing a long press without moving also reports a click on the cell; the drag
                // session is still active at that moment, which is how the two are told apart.
                onClick = {
                    if (item != null && !dragSelect.active) {
                        if (selectionMode) onToggleSelect(item) else onOpen(mediaIndex)
                    }
                },
                onLongClick = { item?.let { if (!selectionMode) onToggleSelect(it) } },
            )
        }
    }
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
 * Two-finger pinch steps the column count. Runs on the Initial pass and consumes only multi-touch
 * events, so single-finger scrolling and taps are left to the grid untouched.
 */
private suspend fun PointerInputScope.detectColumnPinch(onStep: (delta: Int) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var accumulated = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) accumulated = applyPinch(event, accumulated, onStep)
        } while (event.changes.any { it.pressed })
    }
}

private fun applyPinch(event: PointerEvent, accumulated: Float, onStep: (Int) -> Unit): Float {
    event.changes.forEach { it.consume() }
    val total = accumulated * event.calculateZoom()
    return when {
        total > PINCH_OUT_THRESHOLD -> {
            onStep(-1)
            1f
        }
        total < PINCH_IN_THRESHOLD -> {
            onStep(+1)
            1f
        }
        else -> total
    }
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
            val delta = edgeScrollDelta(drag.pointer.y, state.layoutInfo.viewportSize.height.toFloat(), edgePx)
            if (delta != 0f) {
                state.scrollBy(delta)
                drag.reselect()
            }
            delay(FRAME_DELAY_MS)
        }
    }
    return drag
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
