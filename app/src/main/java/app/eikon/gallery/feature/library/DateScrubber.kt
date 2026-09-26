package app.eikon.gallery.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.domain.TimelineLayout
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private val ThumbTouchWidth = 36.dp
private val ThumbHeight = 56.dp
private val ThumbBarSize = DpSize(6.dp, 40.dp)
private val BubbleRoom = 168.dp
private const val HIDE_DELAY_MS = 1_500L
private const val SCRUBBER_MIN_ITEMS = 60

/**
 * Fast scroller with a month/year bubble, for jumping around very large libraries. It appears while
 * the grid scrolls and only the thumb itself takes touches, so photos along the screen edge stay
 * tappable when it is hidden.
 */
@Composable
fun DateScrubber(
    state: LazyGridState,
    layout: TimelineLayout,
    monthLabel: (bucket: String) -> String,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var trackHeightPx by remember { mutableIntStateOf(0) }
    val thumbPx = with(LocalDensity.current) { ThumbHeight.toPx() }
    val total = layout.gridItemCount
    val scrollFraction by remember(total) {
        derivedStateOf { if (total <= 1) 0f else (state.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f) }
    }
    val currentScrollFraction by rememberUpdatedState(scrollFraction)
    val scrolling = state.isScrollInProgress
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            visible = true
        } else {
            delay(HIDE_DELAY_MS)
            visible = false
        }
    }

    val fraction = if (dragging) dragFraction else scrollFraction
    val travel = (trackHeightPx - thumbPx).coerceAtLeast(1f)
    val onDragStart = {
        dragging = true
        dragFraction = currentScrollFraction
    }
    val onDrag = { delta: Float ->
        dragFraction = (dragFraction + delta / travel).coerceIn(0f, 1f)
        state.requestScrollToItem((dragFraction * (total - 1)).roundToInt())
    }
    Box(modifier.fillMaxHeight().width(ThumbTouchWidth + BubbleRoom).onSizeChanged { trackHeightPx = it.height }) {
        AnimatedVisibility(
            visible = visible && total > SCRUBBER_MIN_ITEMS,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (fraction * travel).roundToInt()) },
        ) {
            ScrubberThumb(
                bubbleText = if (dragging) bubbleLabel(layout, fraction, monthLabel) else null,
                dragKey = travel,
                onDragStart = onDragStart,
                onDrag = onDrag,
                onDragEnd = { dragging = false },
            )
        }
    }
}

private fun bubbleLabel(layout: TimelineLayout, fraction: Float, monthLabel: (String) -> String): String? {
    val position = (fraction * (layout.gridItemCount - 1)).roundToInt()
    val section = layout.sectionIndexOfGridPosition(position)
    return if (section < 0) null else monthLabel(layout.sections[section].bucket)
}

@Composable
private fun ScrubberThumb(
    bubbleText: String?,
    dragKey: Any,
    onDragStart: () -> Unit,
    onDrag: (delta: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.scrubber_description)
    Row(
        modifier = modifier.fillMaxWidth().height(ThumbHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (bubbleText != null) Bubble(bubbleText)
        }
        Box(
            modifier = Modifier
                .width(ThumbTouchWidth)
                .height(ThumbHeight)
                .semantics { contentDescription = description }
                .dragToScrub(dragKey, onDragStart, onDrag, onDragEnd),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .padding(end = 2.dp)
                    .size(ThumbBarSize)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f), RoundedCornerShape(50)),
            )
        }
    }
}

/** Follows a finger dragging up and down, reporting each step in pixels. */
private fun Modifier.dragToScrub(key: Any, onStart: () -> Unit, onDrag: (delta: Float) -> Unit, onEnd: () -> Unit): Modifier = pointerInput(key) {
    detectVerticalDragGestures(
        onDragStart = { onStart() },
        onDragEnd = onEnd,
        onDragCancel = onEnd,
        onVerticalDrag = { change, delta ->
            change.consume()
            onDrag(delta)
        },
    )
}

@Composable
private fun Bubble(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        modifier = Modifier.padding(end = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}
