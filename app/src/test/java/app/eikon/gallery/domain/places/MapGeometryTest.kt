package app.eikon.gallery.domain.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapGeometryTest {
    @Test
    fun theProjectionPutsTheOriginInTheMiddleAndRoundTrips() {
        assertEquals(0.5, MapProjection.x(0.0), 1e-9)
        assertEquals(0.5, MapProjection.y(0.0), 1e-9)
        for ((lat, lon) in listOf(41.9 to 12.5, -33.9 to 151.2, 64.1 to -21.9, 0.0 to -180.0, 85.0 to 179.9)) {
            assertEquals(lat, MapProjection.latitude(MapProjection.y(lat)), 1e-6)
            assertEquals(lon, MapProjection.longitude(MapProjection.x(lon)), 1e-9)
        }
    }

    @Test
    fun northIsUpAndTheProjectionStopsNearTheEdgeOfTheWorld() {
        assertTrue(MapProjection.y(60.0) < MapProjection.y(30.0))
        assertEquals(0.0, MapProjection.y(89.9), 1e-3)
        assertEquals(1.0, MapProjection.y(-89.9), 1e-3)
    }

    private val width = 1000.0
    private val height = 800.0

    @Test
    fun zoomingKeepsThePointUnderTheFingerWhereItIs() {
        val view = MapViewport(0.4, 0.3, 4_000.0).clamped(width, height)
        val anchorX = 700.0
        val anchorY = 200.0
        val before = view.worldX(anchorX, width) to view.worldY(anchorY, height)

        val zoomed = view.zoomed(2.5, anchorX, anchorY, width, height)

        assertEquals(before.first, zoomed.worldX(anchorX, width), 1e-9)
        assertEquals(before.second, zoomed.worldY(anchorY, height), 1e-9)
        assertEquals(10_000.0, zoomed.worldPixels, 1e-6)
    }

    @Test
    fun theViewNeverLeavesTheWorldOrZoomsOutFurtherThanTheWholeWorld() {
        val out = MapViewport(0.5, 0.5, 4_000.0).zoomed(0.0001, 500.0, 400.0, width, height)
        assertEquals(MapViewport.minWorldPixels(width, height), out.worldPixels, 1e-9)

        val dragged = MapViewport(0.5, 0.5, 8_000.0).panned(1e9, 1e9, width, height)
        assertTrue(dragged.centerX in 0.0..1.0 && dragged.centerY in 0.0..1.0)
        val edge = dragged.worldX(width, width)
        assertTrue("the right edge of the screen stays within the world: $edge", dragged.worldX(0.0, width) >= -1e-9)
    }

    @Test
    fun theZoomHasAnUpperLimit() {
        val closest = MapViewport(0.5, 0.5, 4_000.0).zoomed(1e12, 500.0, 400.0, width, height)
        assertEquals(MapViewport.MAX_WORLD_PIXELS, closest.worldPixels, 1e-6)
    }

    @Test
    fun aViewportAroundABoxContainsTheBox() {
        val view = MapViewport.around(41.0, 43.0, 11.0, 14.0, width, height)
        for ((lat, lon) in listOf(41.0 to 11.0, 43.0 to 14.0)) {
            val x = view.screenX(MapProjection.x(lon), width)
            val y = view.screenY(MapProjection.y(lat), height)
            assertTrue("$lat,$lon at $x,$y", x in 0.0..width && y in 0.0..height)
        }
    }

    // --- clustering ---------------------------------------------------------------------------------

    private fun points(vararg latLon: Pair<Double, Double>) =
        GeoPoints.ofLatLon(DoubleArray(latLon.size) { latLon[it].first }, DoubleArray(latLon.size) { latLon[it].second })

    @Test
    fun nearbyPhotosMergeIntoOneMarkerAtTheirAveragePosition() {
        val view = MapViewport(MapProjection.x(12.5), MapProjection.y(41.9), 20_000_000.0)
        val cluster = PointClusterer.cluster(points(41.9 to 12.5, 41.9001 to 12.5001, 41.8999 to 12.4999), view, width, height, 40.0).single()

        assertEquals(3, cluster.count)
        assertEquals(41.9, cluster.latitude, 1e-3)
        assertEquals(12.5, cluster.longitude, 1e-3)
    }

    @Test
    fun farApartPhotosStaySeparateAndZoomingOutMergesThem() {
        val rome = 41.9 to 12.5
        val milan = 45.5 to 9.2
        val close = MapViewport(MapProjection.x(11.0), MapProjection.y(43.7), 50_000.0)
        assertEquals(2, PointClusterer.cluster(points(rome, milan), close, width, height, 40.0).size)

        val far = MapViewport(MapProjection.x(11.0), MapProjection.y(43.7), 1_500.0)
        assertEquals(2, PointClusterer.cluster(points(rome, milan), far, width, height, 40.0).single().count)
    }

    @Test
    fun aGroupSplitByAGridLineIsStillOneMarker() {
        // Two photos 20 pixels apart, whatever the grid lines: found by trying every offset of the pair against a 40 px grid.
        val world = 20_000_000.0
        for (offset in 0 until 40 step 3) {
            val lon = 12.5 + offset * 360.0 / world / 4
            val view = MapViewport(MapProjection.x(12.5), MapProjection.y(41.9), world)
            val clusters = PointClusterer.cluster(points(41.9 to lon, 41.9 to lon + 20 * 360.0 / world), view, width, height, 40.0)
            assertEquals("offset $offset", 1, clusters.size)
        }
    }

    @Test
    fun photosOutsideTheScreenAreLeftOut() {
        val view = MapViewport(MapProjection.x(12.5), MapProjection.y(41.9), 2_000_000.0)
        val clusters = PointClusterer.cluster(points(41.9 to 12.5, -33.9 to 151.2), view, width, height, 40.0)
        assertEquals(1, clusters.size)
    }

    @Test
    fun aClustersBoundsCoverItsPhotos() {
        val view = MapViewport(MapProjection.x(12.5), MapProjection.y(41.9), 1_000_000.0)
        val cluster = PointClusterer.cluster(points(41.90 to 12.50, 41.92 to 12.53), view, width, height, 200.0).single()
        // Positions are kept as floats (about 2 metres of resolution), which is why the app widens a tapped box by 0.0001 degrees.
        assertTrue(cluster.minLatitude <= 41.90 + 1e-4 && cluster.maxLatitude >= 41.92 - 1e-4)
        assertTrue(cluster.minLongitude <= 12.50 + 1e-4 && cluster.maxLongitude >= 12.53 - 1e-4)
    }
}
