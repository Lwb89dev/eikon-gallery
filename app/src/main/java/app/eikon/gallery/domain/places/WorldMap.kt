package app.eikon.gallery.domain.places

/**
 * The outlines of the countries drawn behind the photo markers, decoded from `places/world.bin` (Natural Earth,
 * public domain; see tools/build_worldmap.py for the format). Each outline is a closed ring of x, y pairs in the world
 * coordinates of [MapProjection]. It is only for orientation: there are no roads, no names and no imagery, so
 * nothing about a map ever has to be downloaded.
 */
class WorldMap(val rings: List<FloatArray>) {
    companion object {
        private val MAGIC = byteArrayOf('E'.code.toByte(), 'K'.code.toByte(), 'W'.code.toByte(), 'M'.code.toByte())
        private const val VERSION = 1
        private const val DEGREE_UNITS = 10_000.0

        fun decode(bytes: ByteArray): WorldMap {
            require(bytes.size > MAGIC.size + 1 && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not a world map file" }
            require(bytes[MAGIC.size].toInt() == VERSION) { "unsupported world map version" }
            val reader = Reader(bytes, MAGIC.size + 1)
            val count = reader.varint().toInt()
            return WorldMap(List(count) { reader.ring() })
        }
    }

    private class Reader(private val bytes: ByteArray, private var position: Int) {
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[position++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        private fun int32(): Int {
            var v = 0
            repeat(4) { v = (v shl 8) or (bytes[position++].toInt() and 0xFF) }
            return v
        }

        private fun zigzag(): Long {
            val v = varint()
            return (v ushr 1) xor -(v and 1)
        }

        fun ring(): FloatArray {
            val points = varint().toInt()
            val out = FloatArray(points * 2)
            var longitude = int32().toLong()
            var latitude = int32().toLong()
            for (i in 0 until points) {
                if (i > 0) {
                    longitude += zigzag()
                    latitude += zigzag()
                }
                out[2 * i] = MapProjection.x(longitude / DEGREE_UNITS).toFloat()
                out[2 * i + 1] = MapProjection.y(latitude / DEGREE_UNITS).toFloat()
            }
            return out
        }
    }
}
