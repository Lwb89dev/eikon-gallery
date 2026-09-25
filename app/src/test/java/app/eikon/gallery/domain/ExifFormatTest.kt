package app.eikon.gallery.domain

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExifFormatTest {
    @Test
    fun apertureDropsTrailingZeros() {
        assertEquals("f/1.8", ExifFormat.aperture(1.8))
        assertEquals("f/2", ExifFormat.aperture(2.0))
        assertEquals("f/1.7", ExifFormat.aperture(1.69))
        assertNull(ExifFormat.aperture(0.0))
        assertNull(ExifFormat.aperture(null))
    }

    @Test
    fun shutterUsesFractionsBelowOneSecond() {
        assertEquals("1/120 s", ExifFormat.shutter(1.0 / 120))
        assertEquals("1/4000 s", ExifFormat.shutter(0.00025))
        assertEquals("1 s", ExifFormat.shutter(1.0))
        assertEquals("2.5 s", ExifFormat.shutter(2.5))
        assertNull(ExifFormat.shutter(0.0))
        assertNull(ExifFormat.shutter(null))
    }

    @Test
    fun focalLengthAndIso() {
        assertEquals("5.4 mm", ExifFormat.focalLength(5.4))
        assertEquals("24 mm", ExifFormat.focalLength(24.0))
        assertEquals("ISO 400", ExifFormat.iso(400))
        assertNull(ExifFormat.iso(0))
        assertNull(ExifFormat.iso(null))
    }

    @Test
    fun exposureBiasIsSigned() {
        assertEquals("+0.33 EV", ExifFormat.exposureBias(0.33))
        assertEquals("-1 EV", ExifFormat.exposureBias(-1.0))
        assertEquals("0 EV", ExifFormat.exposureBias(0.0))
    }

    @Test
    fun megapixelsAndResolution() {
        assertEquals("12.2 MP", ExifFormat.megapixels(4032, 3024))
        assertEquals("49.9 MP", ExifFormat.megapixels(8160, 6120))
        assertEquals("12 MP", ExifFormat.megapixels(4000, 3000))
        assertNull(ExifFormat.megapixels(0, 100))
        assertEquals("4032 × 3024", ExifFormat.resolution(4032, 3024))
        assertNull(ExifFormat.resolution(0, 0))
    }

    @Test
    fun durationHasHoursOnlyWhenNeeded() {
        assertEquals("0:05", ExifFormat.duration(5_400))
        assertEquals("1:05", ExifFormat.duration(65_000))
        assertEquals("1:02:03", ExifFormat.duration(3_723_000))
        assertEquals("0:00", ExifFormat.duration(-5))
    }

    @Test
    fun bitrateAndFrameRate() {
        assertEquals("16.5 Mbps", ExifFormat.bitrate(16_500_000))
        assertEquals("640 kbps", ExifFormat.bitrate(640_000))
        assertNull(ExifFormat.bitrate(0))
        assertEquals("29.97 fps", ExifFormat.frameRate(29.97))
        assertEquals("30 fps", ExifFormat.frameRate(30.0))
        assertNull(ExifFormat.frameRate(null))
    }

    @Test
    fun codecsGetReadableNames() {
        assertEquals("H.264 (AVC)", ExifFormat.videoCodec("video/avc"))
        assertEquals("H.265 (HEVC)", ExifFormat.videoCodec("video/hevc"))
        assertEquals("AV1", ExifFormat.videoCodec("video/av01"))
        assertEquals("custom", ExifFormat.videoCodec("video/custom"))
        assertEquals("AAC", ExifFormat.audioCodec("audio/mp4a-latm"))
        assertNull(ExifFormat.videoCodec(null))
    }

    @Test
    fun captureTimeParsesExifDateAndOffset() {
        val time = ExifFormat.captureTime("2025:08:14 15:32:10", "+02:00")!!
        assertEquals(LocalDateTime.of(2025, 8, 14, 15, 32, 10), time.local)
        assertEquals(ZoneOffset.ofHours(2), time.offset)
    }

    @Test
    fun captureTimeToleratesMissingOrBrokenParts() {
        assertNull(ExifFormat.captureTime("2025:08:14 15:32:10", null)!!.offset)
        assertNull(ExifFormat.captureTime("2025:08:14 15:32:10", "garbage")!!.offset)
        assertNull(ExifFormat.captureTime("0000:00:00 00:00:00", null))
        assertNull(ExifFormat.captureTime("", null))
        assertNull(ExifFormat.captureTime(null, null))
    }

    @Test
    fun iso6709LocationIsParsed() {
        assertEquals(GeoPoint(37.422, -122.0841), ExifFormat.parseIso6709("+37.4220-122.0841/"))
        assertEquals(GeoPoint(-33.8688, 151.2093), ExifFormat.parseIso6709("-33.8688+151.2093+012.000/"))
    }

    @Test
    fun invalidLocationsAreRejected() {
        assertNull(ExifFormat.parseIso6709(null))
        assertNull(ExifFormat.parseIso6709("nonsense"))
        assertNull(ExifFormat.parseIso6709("+95.0000+010.0000/"))
    }

    @Test
    fun nullIslandMeansNoFix() {
        assertNull(ExifFormat.geoPoint(0.0, 0.0))
        assertNull(ExifFormat.parseIso6709("+00.0000+000.0000/"))
        assertEquals(GeoPoint(0.0, 12.5), ExifFormat.geoPoint(0.0, 12.5))
        assertNull(ExifFormat.geoPoint(10.0, 181.0))
    }

    @Test
    fun deviceNameAvoidsRepeatingTheMake() {
        assertEquals("Google Pixel 8", ExifFormat.deviceName("Google", "Pixel 8"))
        assertEquals("samsung SM-S918B", ExifFormat.deviceName("samsung", "SM-S918B"))
        assertEquals("Canon EOS R5", ExifFormat.deviceName("Canon", "Canon EOS R5"))
        assertEquals("Pixel 8", ExifFormat.deviceName(null, " Pixel 8 "))
        assertEquals("Google", ExifFormat.deviceName("Google", null))
        assertNull(ExifFormat.deviceName("", " "))
    }

    @Test
    fun orientationIsReportedAsRotation() {
        assertEquals("0°", ExifFormat.orientation(1))
        assertEquals("90°", ExifFormat.orientation(6))
        assertEquals("270°", ExifFormat.orientation(8))
        assertEquals("180° ↔", ExifFormat.orientation(4))
        assertNull(ExifFormat.orientation(0))
        assertNull(ExifFormat.orientation(99))
    }

    @Test
    fun onlySidewaysOrientationsSwapAxes() {
        assertEquals(listOf(false, false, false, false, true, true, true, true), (1..8).map(ExifFormat::orientationSwapsAxes))
        assertEquals(false, ExifFormat.orientationSwapsAxes(0))
    }
}
