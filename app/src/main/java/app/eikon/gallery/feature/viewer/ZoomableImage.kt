package app.eikon.gallery.feature.viewer

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.domain.MediaItem
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import kotlin.math.abs
import kotlinx.coroutines.launch

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOMED_THRESHOLD = 1.02f
private const val ZOOM_ANIMATION_MS = 260

/** Longest edge requested once the user zooms in; bounds memory to about 64 MB for one bitmap. */
private const val HIGH_RES_EDGE_PX = 4096

/**
 * Pan/zoom state of one photo. The image is drawn fitted into the container at scale 1; zooming
 * scales around the gesture's centroid and the offset is clamped so the image never leaves the
 * screen further than its own edge.
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(MIN_SCALE)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var containerSize by mutableStateOf(IntSize.Zero)

    /** Width / height of the image as displayed; 0 until the first bitmap has loaded. */
    var imageAspect by mutableFloatStateOf(0f)

    val isZoomed: Boolean get() = scale > ZOOMED_THRESHOLD

    fun reset() {
        scale = MIN_SCALE
        offset = Offset.Zero
    }

    /**
     * Applies one pinch/pan step. [centroid] is relative to the container centre. Returns true when
     * the gesture moved the image, false when it pushed against an edge (so the pager may take over).
     */
    fun transform(centroid: Offset, pan: Offset, zoom: Float): Boolean {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = newScale / scale
        val previous = offset
        val target = clamp(centroid - (centroid - previous) * ratio + pan, newScale)
        scale = newScale
        offset = target
        if (ratio != 1f) return true
        return if (abs(pan.x) >= abs(pan.y)) target.x != previous.x else target.y != previous.y
    }

    suspend fun toggleZoom(tap: Offset) {
        if (isZoomed) {
            animateTo(MIN_SCALE, Offset.Zero)
            return
        }
        val ratio = DOUBLE_TAP_SCALE / scale
        animateTo(DOUBLE_TAP_SCALE, clamp(tap - (tap - offset) * ratio, DOUBLE_TAP_SCALE))
    }

    private suspend fun animateTo(targetScale: Float, targetOffset: Offset) {
        val startScale = scale
        val startOffset = offset
        animate(0f, 1f, animationSpec = tween(ZOOM_ANIMATION_MS)) { progress, _ ->
            scale = lerp(startScale, targetScale, progress)
            offset = lerp(startOffset, targetOffset, progress)
        }
    }

    private fun fittedSize(): Size {
        val width = containerSize.width.toFloat()
        val height = containerSize.height.toFloat()
        if (imageAspect <= 0f || width <= 0f || height <= 0f) return Size(width, height)
        return if (imageAspect > width / height) Size(width, width / imageAspect) else Size(height * imageAspect, height)
    }

    private fun clamp(candidate: Offset, forScale: Float): Offset {
        val fitted = fittedSize()
        val maxX = ((fitted.width * forScale - containerSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((fitted.height * forScale - containerSize.height) / 2f).coerceAtLeast(0f)
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }
}

/**
 * Container that adds pinch, pan and double-tap zoom to its [content].
 *
 * At scale 1 a single finger is left alone so the pager can swipe and the viewer can drag to
 * dismiss; once zoomed, single-finger pans move the image and only overflow at an edge is passed on.
 */
@Composable
fun ZoomableBox(
    state: ZoomState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { state.containerSize = it }
            .pointerInput(state) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap -> scope.launch { state.toggleZoom(tap - centerOf(size)) } },
                )
            }
            .pointerInput(state) { detectPinchAndPan(state) },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.offset.x
                    translationY = state.offset.y
                },
        ) { content() }
    }
}

private fun centerOf(size: IntSize) = Offset(size.width / 2f, size.height / 2f)

private suspend fun PointerInputScope.detectPinchAndPan(state: ZoomState) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val center = centerOf(size)
        do {
            val event = awaitPointerEvent()
            applyGesture(event, state, center)
        } while (event.changes.any { it.pressed })
    }
}

private fun applyGesture(event: PointerEvent, state: ZoomState, center: Offset) {
    val multiTouch = event.changes.count { it.pressed } > 1
    if (!multiTouch && !state.isZoomed) return
    val zoom = event.calculateZoom()
    val pan = event.calculatePan()
    if (zoom == 1f && pan == Offset.Zero) return
    val absorbed = state.transform(event.calculateCentroid(useCurrent = false) - center, pan, zoom)
    if (multiTouch || absorbed) {
        event.changes.forEach { if (it.positionChanged()) it.consume() }
    }
}

/**
 * A photo page: thumbnail instantly, then a screen-sized decode, then (only while zoomed in) a much
 * larger one. Each layer is dropped when it is no longer needed so paging through a long sequence of
 * photos never accumulates full-size bitmaps.
 */
@Composable
fun ImagePage(
    item: MediaItem,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = remember(item.id) { ZoomState() }
    LaunchedEffect(isCurrent) { if (!isCurrent) state.reset() }
    if (isCurrent) {
        LaunchedEffect(state) { snapshotFlow { state.isZoomed }.collect(onZoomedChange) }
    }
    ZoomableBox(state, onTap, modifier) { LayeredImage(item, state) }
}

@Composable
private fun LayeredImage(item: MediaItem, state: ZoomState) {
    val context = LocalPlatformContext.current
    var previewLoaded by remember(item.id) { mutableStateOf(false) }
    if (!previewLoaded) MediaThumbnail(item, Modifier.fillMaxSize(), ContentScale.Fit)

    val container = state.containerSize
    if (container != IntSize.Zero) {
        val preview = remember(item.id, container) {
            ImageRequest.Builder(context).data(item.uri).size(CoilSize(container.width, container.height)).build()
        }
        AsyncImage(
            model = preview,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = {
                state.imageAspect = it.result.image.width.toFloat() / it.result.image.height
                previewLoaded = true
            },
        )
    }
    if (state.isZoomed) HighResLayer(item)
}

@Composable
private fun HighResLayer(item: MediaItem) {
    val context = LocalPlatformContext.current
    val request = remember(item.id) {
        ImageRequest.Builder(context).data(item.uri).size(CoilSize(HIGH_RES_EDGE_PX, HIGH_RES_EDGE_PX)).build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}
