package app.eikon.gallery.domain

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotionPhotoDetectorTest {
    private fun header(xmp: String, junkBefore: Int = 0, junkAfter: Int = 0): ByteArray {
        // A little binary padding on each side, as the real XMP packet sits inside a JPEG APP1 segment among other bytes.
        val before = ByteArray(junkBefore) { (it % 256).toByte() }
        val after = ByteArray(junkAfter) { (255 - it % 256).toByte() }
        return before + xmp.toByteArray(StandardCharsets.ISO_8859_1) + after
    }

    private fun containerDirectory(vararg items: Triple<String, Long, Long?>): String {
        val elements = items.joinToString("") { (semantic, length, padding) ->
            val paddingAttr = padding?.let { " Item:Padding=\"$it\"" }.orEmpty()
            "<Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"$semantic\" Item:Length=\"$length\"$paddingAttr/>"
        }
        return "<x:xmpmeta><rdf:RDF><rdf:Description><Container:Directory><rdf:Seq>$elements</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"
    }

    // --- Container Directory (Pixel) -----------------------------------------------------------------

    @Test
    fun theClipStartsAsFarFromTheEndAsItsOwnDeclaredLength() {
        val xmp = containerDirectory(Triple("Primary", 0, null), Triple("MotionPhoto", 500_000, null))
        val fileSize = 2_000_000L

        val offset = MotionPhotoDetector.findVideoOffset(header(xmp), fileSize)

        assertEquals(fileSize - 500_000, offset)
    }

    @Test
    fun theOrderOfTheAttributesInsideOneItemDoesNotMatter() {
        val xmp = "<Container:Item Item:Length=\"300\" Item:Semantic=\"MotionPhoto\" Item:Mime=\"video/mp4\"/>"
        assertEquals(1_000L - 300, MotionPhotoDetector.findVideoOffset(header(xmp), 1_000))
    }

    @Test
    fun anItemListedAfterTheClipPushesItsOffsetFurtherFromTheEnd() {
        // A depth map or another extra saved after the clip: the clip is not the last bytes of the file, so both lengths count.
        val xmp = containerDirectory(Triple("Primary", 0, null), Triple("MotionPhoto", 500_000, null), Triple("Depth", 20_000, null))
        assertEquals(2_000_000L - 500_000 - 20_000, MotionPhotoDetector.findVideoOffset(header(xmp), 2_000_000))
    }

    @Test
    fun paddingAfterTheClipCountsTowardsItsOffsetToo() {
        val xmp = containerDirectory(Triple("Primary", 0, null), Triple("MotionPhoto", 500_000, 8))
        assertEquals(2_000_000L - 500_000 - 8, MotionPhotoDetector.findVideoOffset(header(xmp), 2_000_000))
    }

    @Test
    fun aPictureThatIsOnlyTheStillPhotoHasNoMotionItemAndIsNotOne() {
        val xmp = containerDirectory(Triple("Primary", 0, null))
        assertNull(MotionPhotoDetector.findVideoOffset(header(xmp), 2_000_000))
    }

    @Test
    fun realWorldSurroundingBytesDoNotConfuseTheParser() {
        val xmp = containerDirectory(Triple("Primary", 0, null), Triple("MotionPhoto", 123_456, null))
        val offset = MotionPhotoDetector.findVideoOffset(header(xmp, junkBefore = 4_096, junkAfter = 4_096), 3_000_000)
        assertEquals(3_000_000L - 123_456, offset)
    }

    // --- MicroVideo (early Google Camera / Samsung) --------------------------------------------------

    @Test
    fun aMicroVideoIsPlacedByHowFarItsOffsetSaysFromTheEndOfTheFile() {
        val xmp = "<rdf:Description GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"777000\"/>"
        assertEquals(5_000_000L - 777_000, MotionPhotoDetector.findVideoOffset(header(xmp), 5_000_000))
    }

    @Test
    fun theContainerDirectorySchemaWinsWhenAFileSomehowHasBoth() {
        val xmp = containerDirectory(Triple("Primary", 0, null), Triple("MotionPhoto", 500_000, null)) +
            "<rdf:Description GCamera:MicroVideoOffset=\"999\"/>"
        assertEquals(2_000_000L - 500_000, MotionPhotoDetector.findVideoOffset(header(xmp), 2_000_000))
    }

    // --- not a Motion Photo, or a corrupt one -------------------------------------------------------

    @Test
    fun aPlainPhotoWithNeitherMarkerIsNotAMotionPhoto() {
        assertNull(MotionPhotoDetector.findVideoOffset(header("<rdf:Description exif:Make=\"Google\"/>"), 2_000_000))
    }

    @Test
    fun anEmptyHeaderIsNotAMotionPhoto() {
        assertNull(MotionPhotoDetector.findVideoOffset(ByteArray(0), 2_000_000))
    }

    @Test
    fun aDeclaredLengthLargerThanTheWholeFileIsRejectedRatherThanGivingANegativeOffset() {
        val xmp = containerDirectory(Triple("MotionPhoto", 999_999, null))
        assertNull(MotionPhotoDetector.findVideoOffset(header(xmp), 100))
    }

    @Test
    fun aMicroVideoOffsetOfZeroBytesFromTheEndWouldPointPastTheFileAndIsRejected() {
        // "0 bytes from the end" means the clip starts at the very end of the file: nothing left to play.
        val xmp = "GCamera:MicroVideoOffset=\"0\""
        assertNull(MotionPhotoDetector.findVideoOffset(header(xmp), 2_000_000))
    }

    @Test
    fun aWordThatMerelyContainsMotionPhotoIsNotMistakenForTheSemanticItself() {
        val xmp = "<Container:Item Item:Semantic=\"NotMotionPhotoAtAll\" Item:Length=\"500\"/>"
        assertNull(MotionPhotoDetector.findVideoOffset(header(xmp), 2_000_000))
    }
}
