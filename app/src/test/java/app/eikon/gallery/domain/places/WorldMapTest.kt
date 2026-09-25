package app.eikon.gallery.domain.places

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldMapTest {
    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) out.write(b or 0x80) else { out.write(b); return }
        }
    }

    private fun zigzag(value: Long) = (value shl 1) xor (value shr 63)

    @Test
    fun decodesTheDocumentedFormat() {
        val out = ByteArrayOutputStream()
        out.write("EKWM".toByteArray()); out.write(1)
        varint(out, 1) // one ring
        varint(out, 4) // four points
        for (v in listOf(100_000L, 200_000L)) { out.write(((v shr 24) and 0xFF).toInt()); out.write(((v shr 16) and 0xFF).toInt()); out.write(((v shr 8) and 0xFF).toInt()); out.write((v and 0xFF).toInt()) }
        for ((dx, dy) in listOf(10_000L to 0L, 0L to -10_000L, -10_000L to 10_000L)) { varint(out, zigzag(dx)); varint(out, zigzag(dy)) }

        val ring = WorldMap.decode(out.toByteArray()).rings.single()

        assertEquals(8, ring.size)
        assertEquals(MapProjection.x(10.0).toFloat(), ring[0], 1e-6f) // longitude 100000 / 10000
        assertEquals(MapProjection.y(20.0).toFloat(), ring[1], 1e-6f)
        assertEquals(MapProjection.x(11.0).toFloat(), ring[2], 1e-6f)
        assertEquals(MapProjection.y(19.0).toFloat(), ring[5], 1e-6f)
    }

    @Test
    fun rejectsAFileThatIsNotAWorldMap() {
        assertThrows(IllegalArgumentException::class.java) { WorldMap.decode("nonsense".toByteArray()) }
    }

    private val world: WorldMap by lazy { WorldMap.decode(File("src/main/assets/places/world.bin").readBytes()) }

    private fun inside(rings: List<FloatArray>, latitude: Double, longitude: Double): Boolean {
        val px = MapProjection.x(longitude).toFloat()
        val py = MapProjection.y(latitude).toFloat()
        return rings.any { ring -> contains(ring, px, py) }
    }

    private fun contains(ring: FloatArray, px: Float, py: Float): Boolean {
        var inside = false
        var j = ring.size / 2 - 1
        for (i in 0 until ring.size / 2) {
            val xi = ring[2 * i]; val yi = ring[2 * i + 1]; val xj = ring[2 * j]; val yj = ring[2 * j + 1]
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    @Test
    fun theBundledMapHasTheCountriesOfTheWorld() {
        assertTrue("rings: ${world.rings.size}", world.rings.size > 1_000)
        assertTrue(world.rings.all { ring -> ring.all { it in 0f..1f } })
    }

    @Test
    fun knownCitiesAreOnLandAndTheOpenSeaIsNot() {
        val cities = mapOf("Rome" to (41.9 to 12.5), "Sydney" to (-33.87 to 151.2), "Denver" to (39.74 to -104.99), "Nairobi" to (-1.29 to 36.82), "Reykjavik" to (64.13 to -21.9))
        for ((name, at) in cities) assertTrue("$name should be on land", inside(world.rings, at.first, at.second))
        for ((name, at) in mapOf("mid Atlantic" to (0.0 to -30.0), "south Pacific" to (-40.0 to -140.0), "Indian Ocean" to (-20.0 to 80.0))) {
            assertFalse("$name should be at sea", inside(world.rings, at.first, at.second))
        }
    }
}
