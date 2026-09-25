package app.eikon.gallery.domain.places

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sin

/**
 * Web Mercator, the projection of every familiar web map, with the world scaled to the unit square: x runs from 0
 * (180 degrees west) to 1 (180 east), y from 0 (about 85 north) to 1 (about 85 south).
 */
object MapProjection {
    const val MAX_LATITUDE = 85.0511

    fun x(longitude: Double): Double = (longitude + 180.0) / 360.0

    fun y(latitude: Double): Double {
        val s = sin(Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    fun longitude(x: Double): Double = x * 360.0 - 180.0

    fun latitude(y: Double): Double = Math.toDegrees(atan(kotlin.math.sinh((0.5 - y) * 2 * PI)))
}

/**
 * What part of the world is on screen: the world point at the centre and how many pixels the whole world is wide.
 * Everything here is arithmetic on numbers, so panning and zooming are tested without a screen.
 */
data class MapViewport(val centerX: Double, val centerY: Double, val worldPixels: Double) {
    fun screenX(worldX: Double, widthPx: Double): Double = (worldX - centerX) * worldPixels + widthPx / 2

    fun screenY(worldY: Double, heightPx: Double): Double = (worldY - centerY) * worldPixels + heightPx / 2

    fun worldX(screenX: Double, widthPx: Double): Double = (screenX - widthPx / 2) / worldPixels + centerX

    fun worldY(screenY: Double, heightPx: Double): Double = (screenY - heightPx / 2) / worldPixels + centerY

    /** Moves the map by a drag of ([dx], [dy]) pixels. */
    fun panned(dx: Double, dy: Double, widthPx: Double, heightPx: Double): MapViewport =
        copy(centerX = centerX - dx / worldPixels, centerY = centerY - dy / worldPixels).clamped(widthPx, heightPx)

    /** Zooms by [factor] keeping the world point under ([anchorX], [anchorY]) where it is on screen. */
    fun zoomed(factor: Double, anchorX: Double, anchorY: Double, widthPx: Double, heightPx: Double): MapViewport {
        val minPixels = minWorldPixels(widthPx, heightPx)
        val newPixels = (worldPixels * factor).coerceIn(minPixels, MAX_WORLD_PIXELS)
        val ax = worldX(anchorX, widthPx)
        val ay = worldY(anchorY, heightPx)
        return MapViewport(ax - (anchorX - widthPx / 2) / newPixels, ay - (anchorY - heightPx / 2) / newPixels, newPixels)
            .clamped(widthPx, heightPx)
    }

    /** Keeps the view over the world: never further out than the whole world, never panned into empty space. */
    fun clamped(widthPx: Double, heightPx: Double): MapViewport {
        val pixels = worldPixels.coerceIn(minWorldPixels(widthPx, heightPx), MAX_WORLD_PIXELS)
        val halfW = widthPx / 2 / pixels
        val halfH = heightPx / 2 / pixels
        val x = if (halfW >= 0.5) 0.5 else centerX.coerceIn(halfW, 1 - halfW)
        val y = if (halfH >= 0.5) 0.5 else centerY.coerceIn(halfH, 1 - halfH)
        return MapViewport(x, y, pixels)
    }

    companion object {
        /** About 1.5 metres per pixel at the equator: closer than any photo's GPS is worth. */
        const val MAX_WORLD_PIXELS = 256.0 * 4_194_304

        /** The whole world just fits the screen's shorter side, and the world is never smaller than the screen. */
        fun minWorldPixels(widthPx: Double, heightPx: Double): Double = maxOf(widthPx, heightPx) / 2

        /** A viewport showing the box `[minLon, maxLon] x [minLat, maxLat]` with a margin. */
        fun around(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, widthPx: Double, heightPx: Double): MapViewport {
            val x0 = MapProjection.x(minLon)
            val x1 = MapProjection.x(maxLon)
            val y0 = MapProjection.y(maxLat)
            val y1 = MapProjection.y(minLat)
            val spanX = maxOf(x1 - x0, MIN_SPAN)
            val spanY = maxOf(y1 - y0, MIN_SPAN)
            val pixels = minOf(widthPx / spanX, heightPx / spanY) / MARGIN
            return MapViewport((x0 + x1) / 2, (y0 + y1) / 2, pixels).clamped(widthPx, heightPx)
        }

        private const val MIN_SPAN = 0.0005
        private const val MARGIN = 1.3
    }
}

/** A group of nearby photos shown as one marker, in world coordinates. */
class GeoCluster(val x: Double, val y: Double, val count: Int, val minX: Double, val maxX: Double, val minY: Double, val maxY: Double) {
    val latitude: Double get() = MapProjection.latitude(y)
    val longitude: Double get() = MapProjection.longitude(x)
    val minLatitude: Double get() = MapProjection.latitude(maxY)
    val maxLatitude: Double get() = MapProjection.latitude(minY)
    val minLongitude: Double get() = MapProjection.longitude(minX)
    val maxLongitude: Double get() = MapProjection.longitude(maxX)
}

/** Photo positions in world coordinates, in parallel arrays so 100,000 of them are cheap to scan. */
class GeoPoints(val xs: FloatArray, val ys: FloatArray) {
    init {
        require(xs.size == ys.size) { "x and y counts differ" }
    }

    val size: Int get() = xs.size

    companion object {
        fun ofLatLon(latitudes: DoubleArray, longitudes: DoubleArray): GeoPoints =
            GeoPoints(FloatArray(latitudes.size) { MapProjection.x(longitudes[it]).toFloat() }, FloatArray(latitudes.size) { MapProjection.y(latitudes[it]).toFloat() })
    }
}

/**
 * Merges photos that would land within [cellPixels] of each other on screen into one marker at their average position.
 * Photos are first put into a fixed grid the size of a marker, aligned to the world rather than to the screen, so panning does
 * not reshuffle clusters and zooming in splits them apart. Two markers closer than one cell (a group split by a grid line) are
 * then merged, so markers never overlap. Only photos inside the viewport (plus a margin of one cell) are looked at.
 */
object PointClusterer {
    fun cluster(points: GeoPoints, viewport: MapViewport, widthPx: Double, heightPx: Double, cellPixels: Double): List<GeoCluster> {
        val cell = cellPixels / viewport.worldPixels
        val minX = viewport.worldX(0.0, widthPx) - cell
        val maxX = viewport.worldX(widthPx, widthPx) + cell
        val minY = viewport.worldY(0.0, heightPx) - cell
        val maxY = viewport.worldY(heightPx, heightPx) + cell
        val buckets = HashMap<Long, Accumulator>()
        for (i in 0 until points.size) {
            val x = points.xs[i].toDouble()
            val y = points.ys[i].toDouble()
            if (x < minX || x > maxX || y < minY || y > maxY) continue
            buckets.getOrPut(key(x, y, cell)) { Accumulator() }.add(x, y)
        }
        return mergeNeighbours(buckets.values.sortedByDescending { it.count }, cell).map { it.toCluster() }
    }

    /** Biggest first: each marker absorbs the smaller ones within a cell of its centre. */
    private fun mergeNeighbours(sorted: List<Accumulator>, cell: Double): List<Accumulator> {
        val byCell = HashMap<Long, MutableList<Accumulator>>()
        sorted.forEach { byCell.getOrPut(key(it.centreX, it.centreY, cell)) { mutableListOf() } += it }
        val absorbed = HashSet<Accumulator>()
        val result = ArrayList<Accumulator>()
        for (marker in sorted) {
            if (marker in absorbed) continue
            absorbNear(marker, byCell, absorbed, cell)
            result += marker
        }
        return result
    }

    private fun absorbNear(marker: Accumulator, byCell: Map<Long, List<Accumulator>>, absorbed: MutableSet<Accumulator>, cell: Double) {
        val cx = Math.floor(marker.centreX / cell).toLong()
        val cy = Math.floor(marker.centreY / cell).toLong()
        for (dx in -1L..1L) {
            for (dy in -1L..1L) {
                for (other in byCell[(cx + dx shl KEY_SHIFT) xor ((cy + dy) and KEY_MASK)].orEmpty()) {
                    if (other === marker || other in absorbed) continue
                    if (Math.hypot(other.centreX - marker.centreX, other.centreY - marker.centreY) < cell) {
                        marker.merge(other)
                        absorbed += other
                    }
                }
            }
        }
    }

    private fun key(x: Double, y: Double, cell: Double): Long =
        (Math.floor(x / cell).toLong() shl KEY_SHIFT) xor (Math.floor(y / cell).toLong() and KEY_MASK)

    private class Accumulator {
        var count = 0
        var sumX = 0.0
        var sumY = 0.0
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE

        val centreX: Double get() = sumX / count
        val centreY: Double get() = sumY / count

        fun add(x: Double, y: Double) {
            count++
            sumX += x
            sumY += y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }

        fun merge(other: Accumulator) {
            count += other.count
            sumX += other.sumX
            sumY += other.sumY
            minX = minOf(minX, other.minX)
            maxX = maxOf(maxX, other.maxX)
            minY = minOf(minY, other.minY)
            maxY = maxOf(maxY, other.maxY)
        }

        fun toCluster() = GeoCluster(centreX, centreY, count, minX, maxX, minY, maxY)
    }

    private const val KEY_SHIFT = 32
    private const val KEY_MASK = 0xFFFFFFFFL
}
