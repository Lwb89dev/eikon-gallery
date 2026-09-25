package app.eikon.gallery.data.faces

import app.eikon.gallery.data.embedding.RgbImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Face detection decoding, alignment and clustering, with no model involved. */
class FaceLogicTest {
    // --- YuNet decoding ---------------------------------------------------------------------------

    /** Outputs for a 64 x 32 input in which only stride 8, cell (column 2, row 1) holds a face. */
    private fun outputs(score: Float = 0.9f, cell: Int = 1 * 8 + 2): Map<String, FloatArray> {
        val map = HashMap<String, FloatArray>()
        for (stride in YuNetDecoder.STRIDES) {
            val cells = (64 / stride) * (32 / stride)
            map["cls_$stride"] = FloatArray(cells)
            map["obj_$stride"] = FloatArray(cells)
            map["bbox_$stride"] = FloatArray(cells * 4)
            map["kps_$stride"] = FloatArray(cells * 10)
        }
        map.getValue("cls_8")[cell] = score
        map.getValue("obj_8")[cell] = score
        val box = map.getValue("bbox_8")
        box[cell * 4] = 0.25f // centre x offset within the cell, in cells
        box[cell * 4 + 1] = 0.5f
        box[cell * 4 + 2] = ln(2f) // width = 2 * stride
        box[cell * 4 + 3] = ln(3f) // height = 3 * stride
        val points = map.getValue("kps_8")
        for (i in 0 until 10) points[cell * 10 + i] = if (i % 2 == 0) 0.5f else 1f
        return map
    }

    @Test
    fun aCellWithAHighScoreBecomesAFaceAtTheRightPlace() {
        val faces = YuNetDecoder.decode(outputs(), 64, 32, scoreThreshold = 0.8f, nmsThreshold = 0.3f)

        assertEquals(1, faces.size)
        val face = faces.single()
        assertEquals(0.9f, face.score, 1e-5f)
        // centre = (column 2 + 0.25) * 8 = 18, (row 1 + 0.5) * 8 = 12; size 16 x 24
        assertEquals(18f - 8f, face.left, 1e-4f)
        assertEquals(12f - 12f, face.top, 1e-4f)
        assertEquals(16f, face.width, 1e-4f)
        assertEquals(24f, face.height, 1e-4f)
        // first landmark: (column 2 + 0.5) * 8 = 20, (row 1 + 1) * 8 = 16
        assertEquals(20f, face.landmarks[0], 1e-4f)
        assertEquals(16f, face.landmarks[1], 1e-4f)
    }

    @Test
    fun theScoreIsTheGeometricMeanOfClassAndObjectness() {
        val map = outputs(score = 0.9f)
        map.getValue("cls_8")[1 * 8 + 2] = 1f
        map.getValue("obj_8")[1 * 8 + 2] = 0.64f
        assertEquals(0.8f, YuNetDecoder.decode(map, 64, 32, 0.5f, 0.3f).single().score, 1e-5f)
    }

    @Test
    fun cellsBelowTheThresholdAreIgnored() {
        assertTrue(YuNetDecoder.decode(outputs(score = 0.7f), 64, 32, scoreThreshold = 0.8f, nmsThreshold = 0.3f).isEmpty())
    }

    @Test
    fun overlappingDetectionsOfOneFaceAreMergedKeepingTheBest() {
        val map = outputs(score = 0.95f)
        map.getValue("cls_8")[1 * 8 + 3] = 0.9f // the next cell also fires, on almost the same box
        map.getValue("obj_8")[1 * 8 + 3] = 0.9f
        map.getValue("bbox_8").let { it[(1 * 8 + 3) * 4 + 2] = ln(2f); it[(1 * 8 + 3) * 4 + 3] = ln(3f) }

        val faces = YuNetDecoder.decode(map, 64, 32, 0.8f, 0.3f)

        assertEquals(1, faces.size)
        assertEquals(0.95f, faces.single().score, 1e-5f)
    }

    @Test
    fun farApartFacesAreBothKept() {
        val map = outputs(score = 0.95f, cell = 0)
        map.getValue("cls_8")[3 * 8 + 6] = 0.9f
        map.getValue("obj_8")[3 * 8 + 6] = 0.9f
        val box = map.getValue("bbox_8")
        box[(3 * 8 + 6) * 4 + 2] = ln(2f)
        box[(3 * 8 + 6) * 4 + 3] = ln(3f)
        // grid is 8 columns x 4 rows for a 64 x 32 input
        assertEquals(2, YuNetDecoder.decode(map, 64, 32, 0.8f, 0.3f).size)
    }

    @Test
    fun detectorInputIsBlueGreenRedAndPaddedWithBlack() {
        val image = RgbImage(2, 1, intArrayOf(0xFF102030.toInt(), 0xFF405060.toInt()))
        val tensor = FaceInputs.detectorTensor(image, 4, 2)

        val plane = 4 * 2
        assertEquals(0x30.toFloat(), tensor[0], 0f) // blue of pixel (0, 0)
        assertEquals(0x60.toFloat(), tensor[1], 0f)
        assertEquals(0f, tensor[2], 0f) // padding
        assertEquals(0x20.toFloat(), tensor[plane], 0f) // green
        assertEquals(0x10.toFloat(), tensor[2 * plane], 0f) // red
    }

    // --- Alignment -----------------------------------------------------------------------------------

    private val template = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)

    @Test
    fun landmarksAlreadyInTheStandardPlacesNeedNoTransform() {
        val m = FaceAligner.transform(template)
        assertEquals(1f, m[0], 1e-4f)
        assertEquals(0f, m[1], 1e-4f)
        assertEquals(0f, m[2], 1e-3f)
        assertEquals(1f, m[4], 1e-4f)
    }

    @Test
    fun aScaledRotatedShiftedFaceIsMappedBackOntoTheTemplate() {
        val angle = 0.3
        val scale = 2.5f
        val moved = FloatArray(10)
        for (i in 0 until 5) {
            val x = template[2 * i]
            val y = template[2 * i + 1]
            moved[2 * i] = (scale * (cos(angle) * x - sin(angle) * y) + 40).toFloat()
            moved[2 * i + 1] = (scale * (sin(angle) * x + cos(angle) * y) - 15).toFloat()
        }

        val m = FaceAligner.transform(moved)

        for (i in 0 until 5) {
            val x = m[0] * moved[2 * i] + m[1] * moved[2 * i + 1] + m[2]
            val y = m[3] * moved[2 * i] + m[4] * moved[2 * i + 1] + m[5]
            assertEquals(template[2 * i], x, 1e-2f)
            assertEquals(template[2 * i + 1], y, 1e-2f)
        }
    }

    @Test
    fun cropOfAFlatImageIsFlatInsideAndBlackOutside() {
        val grey = 0xFF808080.toInt()
        val image = RgbImage(60, 60, IntArray(3600) { grey })
        // Landmarks such that the aligned face lies wholly inside the image.
        val landmarks = FloatArray(10) { i -> 10f + template[i] * 0.4f + if (i % 2 == 0) 10f else 5f }

        val crop = FaceAligner.crop(image, landmarks)

        assertEquals(FaceAligner.SIZE, crop.width)
        assertEquals(grey, crop.pixels[56 * FaceAligner.SIZE + 56])
        // A landmark set far off to one side samples outside the picture: black.
        val outside = FaceAligner.crop(image, FloatArray(10) { i -> template[i] * 0.4f - 500f })
        assertEquals(0xFF000000.toInt(), outside.pixels[0])
    }

    // --- Clustering ----------------------------------------------------------------------------------

    private fun unit(vararg values: Float): FloatArray {
        val norm = kotlin.math.sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    @Test
    fun aFaceCloseToAPersonJoinsThemAndADistantOneDoesNot() {
        val clustering = FaceClustering(threshold = 0.5f)
        clustering.add(1, unit(1f, 0f, 0f))
        clustering.add(2, unit(0f, 1f, 0f))

        assertEquals(1L, clustering.match(unit(0.9f, 0.2f, 0f)))
        assertEquals(2L, clustering.match(unit(0.1f, 1f, 0.1f)))
        assertNull(clustering.match(unit(0f, 0f, 1f)))
    }

    @Test
    fun thePersonsAverageMovesAsFacesJoin() {
        val clustering = FaceClustering(threshold = 0.8f)
        clustering.add(1, unit(1f, 0f))
        assertNull(clustering.match(unit(1f, 1f))) // 45 degrees away: below 0.8
        clustering.add(1, unit(1f, 1f))
        clustering.add(1, unit(1f, 1f))
        assertNotNull(clustering.match(unit(1f, 1f))) // the average now leans towards it
    }

    @Test
    fun theBestMatchWinsWhenSeveralAreCloseEnough() {
        val clustering = FaceClustering(threshold = 0.3f)
        clustering.add(1, unit(1f, 0.6f))
        clustering.add(2, unit(1f, 0.05f))
        assertEquals(2L, clustering.match(unit(1f, 0f)))
    }

    @Test
    fun nothingMatchesBeforeAnyoneIsKnown() {
        assertNull(FaceClustering().match(unit(1f, 0f)))
    }

    @Test
    fun forgettingStartsFromScratch() {
        val clustering = FaceClustering()
        clustering.add(1, unit(1f, 0f))
        clustering.forget()
        assertEquals(0, clustering.personCount)
        assertNull(clustering.match(unit(1f, 0f)))
    }

    @Test
    fun exponentialHelperSanity() {
        // exp/ln round trip used by the decoder tests
        assertTrue(abs(exp(ln(3f)) - 3f) < 1e-5f)
    }
}
