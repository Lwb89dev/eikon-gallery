package app.eikon.gallery.feature.places

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.eikon.gallery.R
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.places.GeoCluster
import app.eikon.gallery.domain.places.MapViewport
import app.eikon.gallery.domain.places.PointClusterer
import kotlin.math.hypot
import kotlin.math.ln

/**
 * A map of where the photos were taken, drawn with nothing but country outlines and a marker per group of nearby photos.
 * It needs no map service and shows no imagery, so no coordinate is ever sent anywhere; city names from the bundled
 * place data help orientation once zoomed in. Pinch or double-tap to zoom, drag to pan, tap a marker to see its photos.
 */
@Composable
fun PlacesMap(data: MapData, onOpenArea: (LibraryScope.Area) -> Unit, modifier: Modifier = Modifier) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var viewport by remember(data) { mutableStateOf<MapViewport?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val land = MaterialTheme.colorScheme.surfaceContainerHigh
    val border = MaterialTheme.colorScheme.outlineVariant
    val ocean = MaterialTheme.colorScheme.surfaceContainerLowest
    val marker = MaterialTheme.colorScheme.primary
    val onMarker = MaterialTheme.colorScheme.onPrimary
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val paths = remember(data.world) { data.world.rings.map(::ringPath) }
    val bounds = remember(data.world) { data.world.rings.map(::ringBounds) }
    val cellPx = with(density) { CLUSTER_CELL_DP.dp.toPx() }.toDouble()
    val description = stringResource(R.string.places_map_description)
    // The first view frames the photos; it is set once the size is known, not while drawing.
    LaunchedEffect(data, size) {
        if (viewport == null && size.width > 0 && size.height > 0) viewport = startingViewport(data, size.width.toDouble(), size.height.toDouble())
    }

    Canvas(
        modifier
            .fillMaxSize()
            .background(ocean)
            .semantics { contentDescription = description }
            .onSizeChanged { size = it }
            .pointerInput(data, size) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val current = viewport ?: return@detectTransformGestures
                    val w = size.width.toDouble()
                    val h = size.height.toDouble()
                    viewport = current.panned(pan.x.toDouble(), pan.y.toDouble(), w, h)
                        .zoomed(zoom.toDouble(), centroid.x.toDouble(), centroid.y.toDouble(), w, h)
                }
            }
            .pointerInput(data, size) {
                detectTapGestures(
                    onDoubleTap = { at ->
                        val current = viewport ?: return@detectTapGestures
                        viewport = current.zoomed(DOUBLE_TAP_ZOOM, at.x.toDouble(), at.y.toDouble(), size.width.toDouble(), size.height.toDouble())
                    },
                    onTap = { at ->
                        val current = viewport ?: return@detectTapGestures
                        val clusters = PointClusterer.cluster(data.points, current, size.width.toDouble(), size.height.toDouble(), cellPx)
                        tapped(clusters, current, at, size, cellPx)?.let { onOpenArea(areaOf(it)) }
                    },
                )
            },
    ) {
        if (size.width == 0 || size.height == 0) return@Canvas
        val w = size.width.toDouble()
        val h = size.height.toDouble()
        val view = viewport ?: return@Canvas
        val scale = view.worldPixels.toFloat()
        val left = (w / 2 - view.centerX * view.worldPixels).toFloat()
        val top = (h / 2 - view.centerY * view.worldPixels).toFloat()

        withTransform({ translate(left, top); scale(scale, scale, Offset.Zero) }) {
            for (i in paths.indices) {
                if (!visible(bounds[i], view, w, h)) continue
                drawPath(paths[i], land)
                drawPath(paths[i], border, style = Stroke(width = 0.8.dp.toPx() / scale))
            }
        }
        drawCityLabels(data, view, w, h, textMeasurer, label)
        for (cluster in PointClusterer.cluster(data.points, view, w, h, cellPx)) {
            val at = Offset(view.screenX(cluster.x, w).toFloat(), view.screenY(cluster.y, h).toFloat())
            val radius = markerRadius(cluster.count, cellPx.toFloat())
            drawCircle(marker.copy(alpha = 0.85f), radius, at)
            if (cluster.count > 1) {
                val text = textMeasurer.measure(cluster.count.toString(), TextStyle(color = onMarker, fontSize = 12.sp))
                drawText(text, topLeft = Offset(at.x - text.size.width / 2f, at.y - text.size.height / 2f))
            }
        }
    }
}

private const val CLUSTER_CELL_DP = 44
private const val DOUBLE_TAP_ZOOM = 2.0

/** Frames the photos: the box around all of them, or the whole world when there are none. */
private fun startingViewport(data: MapData, width: Double, height: Double): MapViewport {
    val points = data.points
    if (points.size == 0) return MapViewport(0.5, 0.5, MapViewport.minWorldPixels(width, height)).clamped(width, height)
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (i in 0 until points.size) {
        minX = minOf(minX, points.xs[i]); maxX = maxOf(maxX, points.xs[i])
        minY = minOf(minY, points.ys[i]); maxY = maxOf(maxY, points.ys[i])
    }
    val lonMin = app.eikon.gallery.domain.places.MapProjection.longitude(minX.toDouble())
    val lonMax = app.eikon.gallery.domain.places.MapProjection.longitude(maxX.toDouble())
    val latMin = app.eikon.gallery.domain.places.MapProjection.latitude(maxY.toDouble())
    val latMax = app.eikon.gallery.domain.places.MapProjection.latitude(minY.toDouble())
    return MapViewport.around(latMin, latMax, lonMin, lonMax, width, height)
}

private fun ringPath(ring: FloatArray): Path = Path().apply {
    moveTo(ring[0], ring[1])
    for (i in 1 until ring.size / 2) lineTo(ring[2 * i], ring[2 * i + 1])
    close()
}

private fun ringBounds(ring: FloatArray): FloatArray {
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (i in 0 until ring.size / 2) {
        minX = minOf(minX, ring[2 * i]); maxX = maxOf(maxX, ring[2 * i])
        minY = minOf(minY, ring[2 * i + 1]); maxY = maxOf(maxY, ring[2 * i + 1])
    }
    return floatArrayOf(minX, minY, maxX, maxY)
}

private fun visible(box: FloatArray, view: MapViewport, w: Double, h: Double): Boolean {
    val left = view.worldX(0.0, w)
    val right = view.worldX(w, w)
    val top = view.worldY(0.0, h)
    val bottom = view.worldY(h, h)
    return box[2] >= left && box[0] <= right && box[3] >= top && box[1] <= bottom
}

private fun markerRadius(count: Int, cell: Float): Float = (cell * 0.22f + ln(count.toFloat()) * cell * 0.09f).coerceAtMost(cell * 0.6f)

/** The cluster under the finger, if any: the closest whose marker contains the touch. */
private fun tapped(clusters: List<GeoCluster>, view: MapViewport, at: Offset, size: IntSize, cellPx: Double): GeoCluster? {
    val w = size.width.toDouble()
    val h = size.height.toDouble()
    return clusters
        .map { it to hypot(view.screenX(it.x, w) - at.x, view.screenY(it.y, h) - at.y) }
        .filter { (cluster, distance) -> distance <= markerRadius(cluster.count, cellPx.toFloat()) + TAP_SLOP_PX }
        .minByOrNull { it.second }?.first
}

private const val TAP_SLOP_PX = 12.0

/** The box of a marker's photos, widened a little so photos exactly on its edge are included. */
private fun areaOf(cluster: GeoCluster): LibraryScope.Area = LibraryScope.Area(
    cluster.minLatitude - EDGE, cluster.maxLatitude + EDGE, cluster.minLongitude - EDGE, cluster.maxLongitude + EDGE,
)

private const val EDGE = 0.0001

/** City names for orientation: only the bigger ones far out, more as the map is zoomed in. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCityLabels(
    data: MapData,
    view: MapViewport,
    w: Double,
    h: Double,
    measurer: androidx.compose.ui.text.TextMeasurer,
    color: androidx.compose.ui.graphics.Color,
) {
    val minPopulation = when {
        view.worldPixels < 1_500 -> return
        view.worldPixels < 5_000 -> 5_000_000L
        view.worldPixels < 20_000 -> 1_000_000L
        view.worldPixels < 80_000 -> 250_000L
        else -> 50_000L
    }
    val top = app.eikon.gallery.domain.places.MapProjection.latitude(view.worldY(0.0, h))
    val bottom = app.eikon.gallery.domain.places.MapProjection.latitude(view.worldY(h, h))
    val west = app.eikon.gallery.domain.places.MapProjection.longitude(view.worldX(0.0, w))
    val east = app.eikon.gallery.domain.places.MapProjection.longitude(view.worldX(w, w))
    for (city in data.gazetteer.citiesIn(bottom, top, west, east, minPopulation, MAX_LABELS)) {
        val x = view.screenX(app.eikon.gallery.domain.places.MapProjection.x(city.longitude), w).toFloat()
        val y = view.screenY(app.eikon.gallery.domain.places.MapProjection.y(city.latitude), h).toFloat()
        drawCircle(color, 2.5.dp.toPx(), Offset(x, y))
        val text = measurer.measure(city.name, TextStyle(color = color, fontSize = 11.sp))
        drawText(text, topLeft = Offset(x + 5.dp.toPx(), y - text.size.height / 2f))
    }
}

private const val MAX_LABELS = 30
