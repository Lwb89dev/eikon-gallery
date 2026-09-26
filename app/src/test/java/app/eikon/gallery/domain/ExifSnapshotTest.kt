package app.eikon.gallery.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifSnapshotTest {
    @Test
    fun aSnapshotComesBackExactlyAsItWasWritten() {
        val tags = mapOf("DateTimeOriginal" to "2025:08:12 14:30:05", "OffsetTimeOriginal" to "+02:00", "GPSLatitude" to "41/1,54/1,1000/100")
        assertEquals(tags, ExifSnapshot.decode(ExifSnapshot.encode(tags)))
    }

    @Test
    fun awkwardValuesSurvive() {
        val tags = mapOf("GPSProcessingMethod" to "line one\nline two\r\nwith \\ backslash and = sign", "Empty" to "")
        assertEquals(tags, ExifSnapshot.decode(ExifSnapshot.encode(tags)))
    }

    @Test
    fun noTagsIsAnEmptyTextAndAnEmptyTextIsNoTags() {
        assertEquals("", ExifSnapshot.encode(emptyMap()))
        assertEquals(emptyMap<String, String>(), ExifSnapshot.decode(""))
    }

    @Test
    fun theTextIsTheSameWhateverTheOrderOfTheTags() {
        assertEquals(ExifSnapshot.encode(mapOf("b" to "2", "a" to "1")), ExifSnapshot.encode(mapOf("a" to "1", "b" to "2")))
    }

    @Test
    fun aLineThatIsNotATagIsSkippedNotGuessedAt() {
        assertEquals(mapOf("a" to "1"), ExifSnapshot.decode("a=1\ngarbage\n=novalue"))
    }

    @Test
    fun theFieldsNameTheTagsThatBelongToThem() {
        assertTrue("DateTimeOriginal" in ExifFields.tagsOf("DATE"))
        assertTrue("GPSLatitude" in ExifFields.tagsOf("LOCATION"))
        assertEquals(emptyList<String>(), ExifFields.tagsOf("SOMETHING ELSE"))
    }

    @Test
    fun theTagNamesAreThoseOfTheExifLibrary() {
        // The names are written out here so this test needs no Android classes; they are the values of ExifInterface.TAG_*.
        val dates = listOf("DateTimeOriginal", "DateTimeDigitized", "OffsetTimeOriginal", "OffsetTimeDigitized")
        assertEquals(dates, ExifFields.DATE_TAGS)
        assertEquals("GPSLatitudeRef", ExifFields.GPS_TAGS[1])
    }
}
