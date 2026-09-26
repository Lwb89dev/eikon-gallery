package app.eikon.gallery.domain

import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataEditTest {
    private fun parse(text: String) = CoordinateParser.parse(text)

    private fun assertPoint(lat: Double, lon: Double, point: GeoPoint?) {
        assertEquals("$point", lat, point!!.latitude, 1e-9)
        assertEquals("$point", lon, point.longitude, 1e-9)
    }

    @Test
    fun decimalDegreesWithACommaASpaceOrASemicolon() {
        assertPoint(41.9028, 12.4964, parse("41.9028, 12.4964"))
        assertPoint(41.9028, 12.4964, parse("41.9028,12.4964"))
        assertPoint(41.9028, 12.4964, parse("41.9028 12.4964"))
        assertPoint(41.9028, 12.4964, parse("41.9028; 12.4964"))
        assertPoint(-33.8688, 151.2093, parse("-33.8688, 151.2093"))
    }

    @Test
    fun decimalCommasAreUnderstoodWhenNothingElseIsAmbiguous() {
        assertPoint(41.9028, 12.4964, parse("41,9028 12,4964"))
        assertPoint(41.9028, 12.4964, parse("41,9028; 12,4964"))
    }

    @Test
    fun hemisphereLettersBeforeOrAfterEachNumber() {
        assertPoint(41.9028, 12.4964, parse("41.9028 N 12.4964 E"))
        assertPoint(41.9028, 12.4964, parse("N 41.9028, E 12.4964"))
        assertPoint(-33.8688, 151.2093, parse("33.8688 S, 151.2093 E"))
        assertPoint(40.7128, -74.0060, parse("40.7128 N 74.0060 W"))
        assertPoint(41.9028, 12.4964, parse("n41,9028 e12,4964"))
        assertPoint(-33.8688, -70.6693, parse("S 33.8688; W 70.6693"))
    }

    @Test
    fun aPositionThatIsNotOnEarthIsNotUnderstood() {
        assertNull(parse("91, 10"))
        assertNull(parse("-90.1, 10"))
        assertNull(parse("10, 180.5"))
        assertNull(parse("10, -181"))
        assertPoint(90.0, 180.0, parse("90, 180"))
    }

    @Test
    fun contradictoryOrGarbledTextIsNotGuessedAt() {
        assertNull(parse("-41 S, 12 E"))
        assertNull(parse("41 N, 12 N"))
        assertNull(parse("41 E, 12 N"))
        assertNull(parse("41N9 12E"))
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("Rome"))
        assertNull(parse("41.9"))
        assertNull(parse("41.9, 12.5, 3"))
        assertNull(parse("41.9° N, 12.5° E"))
        assertNull(parse("NaN, 1"))
        assertNull(parse("1e5, 1"))
    }

    @Test
    fun writableFormatsAreTheOnesExifInterfaceCanWrite() {
        assertTrue(EditableFormats.canWrite("image/jpeg"))
        assertTrue(EditableFormats.canWrite("IMAGE/PNG"))
        assertTrue(EditableFormats.canWrite("image/webp"))
        assertFalse(EditableFormats.canWrite("image/heic"))
        assertFalse(EditableFormats.canWrite("video/mp4"))
        assertFalse(EditableFormats.canWrite("image/gif"))
    }

    @Test
    fun datesAreWrittenTheWayExifReadsThem() {
        assertEquals("2025:08:12 14:30:05", ExifText.dateTime(LocalDateTime.of(2025, 8, 12, 14, 30, 5)))
        assertEquals("2025:01:02 03:04:00", ExifText.dateTime(LocalDateTime.of(2025, 1, 2, 3, 4)))
    }

    @Test
    fun aUtcOffsetIsWrittenWithASignAndAColon() {
        assertEquals("+02:00", ExifText.offset(ZoneOffset.ofHours(2)))
        assertEquals("-05:30", ExifText.offset(ZoneOffset.ofHoursMinutes(-5, -30)))
        assertEquals("+00:00", ExifText.offset(ZoneOffset.UTC))
    }
}
