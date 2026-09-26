package app.eikon.gallery.feature.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.core.image.ThumbnailAspects
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.TimelineLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One photo flying between its cell in the grid and its place in the viewer. [cell] and [resting] are where it is at each end (see
 * [HeroGeometry]); [opening] is the direction, cell to viewer or back.
 */
@Immutable
class HeroFlight(val item: MediaItem, val cell: Frame, val resting: Frame, val opening: Boolean) {
    val from: Frame get() = if (opening) cell else resting
    val to: Frame get() = if (opening) resting else cell
}

/**
 * Runs the flight and tells the viewer how much of itself to show meanwhile: while a photo is in the air the viewer's own picture and buttons
 * are hidden (the flying picture stands in for them) and its black backdrop fades in or out with the flight. When nothing is flying the viewer
 * is shown as usual, so a flight that is cancelled or never starts costs nothing but the animation.
 */
@Stable
class HeroController {
    var flight by mutableStateOf<HeroFlight?>(null)
        private set
    private val progress = Animatable(0f)

    /**
     * True from the moment a photo has flown back into the grid until the viewer is opened again. The viewer is then on its way out with nothing left to show:
     * without this, the picture and the black behind it would come back for the length of the viewer's own fade-out and show the photo that was just closed.
     */
    var landedInGrid by mutableStateOf(false)
        private set

    /** False while a photo is flying, and after it has flown back into the grid. */
    val viewerShown: Boolean get() = flight == null && !landedInGrid

    /** The black behind the viewer, 0..1, read while drawing. */
    fun backdrop(): Float {
        if (landedInGrid) return 0f
        val current = flight ?: return 1f
        return if (current.opening) progress.value else 1f - progress.value
    }

    /** The viewer is open again (however it was opened): it shows itself as usual. */
    fun viewerReopened() {
        landedInGrid = false
    }

    /** Where the window through which the flying picture is seen is now. */
    fun window(): Frame? = flight?.let { HeroGeometry.between(it.from, it.to, progress.value) }

    /** Takes off at once (before the caller's next line, so the viewer never shows itself for a frame first) and returns when it has landed or been cancelled. */
    suspend fun fly(next: HeroFlight) {
        landedInGrid = false
        flight = next
        try {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(DURATION_MS, easing = FastOutSlowInEasing))
            if (!next.opening) landedInGrid = true
        } finally {
            if (flight === next) flight = null
        }
    }

    private companion object {
        const val DURATION_MS = 300
    }
}

/** Draws the flying photo above everything else. It takes no touches: the screen underneath is not blocked while it moves. */
@Composable
fun HeroLayer(controller: HeroController, modifier: Modifier = Modifier) {
    val flight = controller.flight ?: return
    val density = LocalDensity.current
    val resting = flight.resting
    Box(modifier.fillMaxSize()) {
        Box(Modifier.inFlightWindow(controller, resting).clipToBounds(), contentAlignment = Alignment.Center) {
            // The picture has the size it has at rest in the viewer and is scaled to cover the window; it is asked for at the size of the cell, which is
            // what is already loaded, so it appears at once.
            MediaThumbnail(
                item = flight.item,
                modifier = Modifier
                    .requiredSize(with(density) { resting.width.toDp() }, with(density) { resting.height.toDp() })
                    .scaledToCover(controller, resting),
                contentScale = ContentScale.Crop,
                requestSize = IntSize(flight.cell.width.roundToInt().coerceAtLeast(1), flight.cell.height.roundToInt().coerceAtLeast(1)),
            )
        }
    }
}

/** Sizes and places what it holds as the window through which the flying picture is seen, wherever the flight has got to. */
private fun Modifier.inFlightWindow(controller: HeroController, resting: Frame): Modifier = layout { measurable, constraints ->
    val window = controller.window() ?: resting
    val placeable = measurable.measure(Constraints.fixed(window.width.roundToInt().coerceAtLeast(1), window.height.roundToInt().coerceAtLeast(1)))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(window.left.roundToInt(), window.top.roundToInt()) }
}

/** Scales the picture, which has its size at rest, up or down until it just covers the window. */
private fun Modifier.scaledToCover(controller: HeroController, resting: Frame): Modifier = graphicsLayer {
    val scale = HeroGeometry.coverScale(controller.window() ?: resting, resting)
    scaleX = scale
    scaleY = scale
}

/**
 * Puts the flights in the grid's screen. It knows where the screen and the grid are (so a cell's place in the grid can be turned into a place on the screen
 * both share) and starts the flight when the viewer opens, and, when it closes, flies back and only then lets the viewer go. Anything that cannot be worked out (a
 * cell that is not on screen, a photo not loaded yet) simply means no flight: the viewer then fades as it always did.
 */
@Stable
class ViewerFlights(private val scope: CoroutineScope) {
    val hero = HeroController()

    /** Set by the screen and the grid as they are laid out; in pixels, in the window's coordinates. */
    var screenOrigin = Offset.Zero
    var screenSize = IntSize.Zero
    var gridOrigin = Offset.Zero
    private var job: Job? = null

    /** Where photo number [mediaIndex] is on the screen right now, or null if its cell is not showing. */
    fun cellFrame(grid: LazyGridState, layout: TimelineLayout, mediaIndex: Int): Frame? {
        val position = layout.gridPositionOfMedia(mediaIndex)
        val info = grid.layoutInfo.visibleItemsInfo.firstOrNull { it.index == position } ?: return null
        val origin = gridOrigin - screenOrigin
        return Frame(origin.x + info.offset.x, origin.y + info.offset.y, info.size.width.toFloat(), info.size.height.toFloat())
    }

    /** Flies [item] from [cell] to its place in the viewer. */
    fun open(item: MediaItem?, cell: Frame?) {
        job?.cancel()
        hero.viewerReopened()
        val flight = flightOf(item, cell, opening = true) ?: return
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) { hero.fly(flight) }
    }

    /** Flies [item] back to [cell], then runs [landed]; with nowhere to fly to, runs it at once. Cancelled by any later flight. */
    fun close(item: MediaItem?, cell: Frame?, landed: () -> Unit) {
        job?.cancel()
        val flight = flightOf(item, cell, opening = false)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (flight != null) hero.fly(flight)
            landed()
        }
    }

    private fun flightOf(item: MediaItem?, cell: Frame?, opening: Boolean): HeroFlight? {
        if (item == null || cell == null || screenSize.width <= 0 || screenSize.height <= 0) return null
        val aspect = ThumbnailAspects.Shared.of(item.id) ?: if (item.height > 0) item.width.toFloat() / item.height else 0f
        return HeroFlight(item, cell, HeroGeometry.fitted(aspect, screenSize.width.toFloat(), screenSize.height.toFloat()), opening)
    }
}
