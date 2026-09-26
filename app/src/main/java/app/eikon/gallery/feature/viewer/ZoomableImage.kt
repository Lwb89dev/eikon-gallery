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
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import app.eikon.gallery.R
import app.eikon.gallery.core.image.EditTransformation
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.GeometryMap
import app.eikon.gallery.domain.MediaItem
import coil3.compose.AsyncImage
import coil3.PlatformContext
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size as CoilSize
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOMED_THRESHOLD = 1.02f
private const val ZOOM_ANIMATION_MS = 260

/** Longest edge requested once the user zooms in; bounds memory to about 64 MB for one bitmap. */
internal const val HIGH_RES_EDGE_PX = 4096

/**
 * How long a photo has to be looked at, still, before its large version starts to be prepared. Decoding one (and, for an edited photo, drawing the edit on it)
 * takes a moment that, started by the first pinch, stops the picture mid-gesture; started here, it is ready by then. Photos that are only swiped past cost nothing.
 */
internal const val HIGH_RES_DWELL_MS = 600L

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

    // Derived, so that what reads it (the picture's layers, the zoom action's label) is composed again when it turns true or false and not at every step of a pinch.
    private val zoomed = derivedStateOf { scale > ZOOMED_THRESHOLD }
    val isZoomed: Boolean get() = zoomed.value

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
    // Pinching and double-tapping cannot be done with a screen reader, so zooming is offered as an action too.
    val zoomLabel = stringResource(if (state.isZoomed) R.string.viewer_zoom_out else R.string.viewer_zoom_in)
    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .semantics {
                customActions = listOf(CustomAccessibilityAction(zoomLabel) { scope.launch { state.toggleZoom(Offset.Zero) }; true })
            }
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
 * photos never accumulates full-size bitmaps. With a [recipe] every layer is drawn edited; the file itself is never touched.
 */
@Composable
fun ImagePage(
    item: MediaItem,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    recipe: EditRecipe? = null,
) {
    val state = remember(item.id) { ZoomState() }
    LaunchedEffect(isCurrent) { if (!isCurrent) state.reset() }
    if (isCurrent) {
        LaunchedEffect(state) { snapshotFlow { state.isZoomed }.collect(onZoomedChange) }
    }
    // Once the photo has been on screen a moment it is worth having its large version ready, out of sight, for the first zoom.
    var restedOn by remember(item.id, recipe) { mutableStateOf(false) }
    LaunchedEffect(isCurrent, item.id, recipe) {
        restedOn = false
        if (!isCurrent) return@LaunchedEffect
        delay(HIGH_RES_DWELL_MS)
        restedOn = true
    }
    ZoomableBox(state, onTap, modifier) { LayeredImage(item, state, recipe, prepareLarge = restedOn) }
}

@Composable
private fun LayeredImage(item: MediaItem, state: ZoomState, recipe: EditRecipe?, prepareLarge: Boolean) {
    val context = LocalPlatformContext.current
    var previewLoaded by remember(item.id, recipe) { mutableStateOf(false) }
    var failed by remember(item.id, recipe) { mutableStateOf(false) }
    if (!previewLoaded) MediaThumbnail(item, Modifier.fillMaxSize(), ContentScale.Fit, applyEdit = recipe != null)

    val container = state.containerSize
    if (container != IntSize.Zero) {
        val preview = remember(item.id, container, recipe) { previewRequest(context, item, recipe, container) }
        AsyncImage(
            model = preview,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = {
                state.imageAspect = it.result.image.width.toFloat() / it.result.image.height
                previewLoaded = true
            },
            onError = { failed = true },
        )
    }
    if (failed) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) { CannotDisplay(Modifier.padding(bottom = 96.dp)) }
    val hasMoreToShow = max(item.width, item.height) > max(container.width, container.height)
    if (state.isZoomed || (prepareLarge && hasMoreToShow)) HighResLayer(item, recipe, state)
}

/** The screen-sized decode; for an edit with a crop the photo is decoded larger, so what is left after cropping still fills the screen. */
private fun previewRequest(context: PlatformContext, item: MediaItem, recipe: EditRecipe?, container: IntSize): ImageRequest {
    val builder = ImageRequest.Builder(context).data(item.uri)
    if (recipe == null) return builder.size(CoilSize(container.width, container.height)).build()
    val map = GeometryMap(item.width.coerceAtLeast(1), item.height.coerceAtLeast(1), recipe.geometry)
    val edge = map.sourceEdgeFor(max(container.width, container.height), HIGH_RES_EDGE_PX)
    return builder.size(CoilSize(edge, edge)).transformations(EditTransformation(recipe)).build()
}

/** The large decode, drawn over the screen-sized one while zoomed; before that, when only being prepared, it is not drawn. */
@Composable
private fun HighResLayer(item: MediaItem, recipe: EditRecipe?, state: ZoomState) {
    val context = LocalPlatformContext.current
    val request = remember(item.id, recipe) {
        val builder = ImageRequest.Builder(context).data(item.uri).size(CoilSize(HIGH_RES_EDGE_PX, HIGH_RES_EDGE_PX))
        recipe?.let { builder.transformations(EditTransformation(it)) }
        builder.build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (state.isZoomed) 1f else 0f },
    )
}
