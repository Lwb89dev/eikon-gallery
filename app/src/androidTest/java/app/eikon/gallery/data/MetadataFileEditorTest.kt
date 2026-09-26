package app.eikon.gallery.data

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.eikon.gallery.data.metadata.MetadataFileEditor
import app.eikon.gallery.domain.ExifFields
import app.eikon.gallery.domain.GeoPoint
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The EXIF changes eikon makes to a photo's working copy, with Android's real ExifInterface, on a copy of a bundled public-domain sample photo in the app's cache: the tags change
 * as asked, everything else in the picture is left alone, the picture still decodes at the same size, and putting a snapshot back restores what was there. It never touches the
 * user's photos.
 */
@RunWith(AndroidJUnit4::class)
class MetadataFileEditorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var file: File

    @Before
    fun copySample() {
        file = File(instrumentation.targetContext.cacheDir, "metadata-editor-test.jpg")
        instrumentation.context.assets.open("embedding/photos/cat.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @After
    fun remove() {
        file.delete()
    }

    private fun exif() = ExifInterface(file.absolutePath)

    private fun size() = BitmapFactory.Options().also { options ->
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, options)
    }.let { it.outWidth to it.outHeight }

    private fun write(tags: List<String>, values: Map<String, String>) = MetadataFileEditor.write(file, tags, values)

    private fun writeLocation(point: GeoPoint) = MetadataFileEditor.writeLocation(file, point)

    private fun snapshot(tags: List<String>): Map<String, String> = MetadataFileEditor.snapshot(file, tags)

    @Test
    fun theDateIsWrittenAndReadBack() {
        val before = size()

        write(ExifFields.DATE_TAGS, mapOf("DateTimeOriginal" to "2020:02:29 23:59:58", "DateTimeDigitized" to "2020:02:29 23:59:58", "OffsetTimeOriginal" to "+01:00", "OffsetTimeDigitized" to "+01:00"))

        assertEquals("2020:02:29 23:59:58", exif().getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("+01:00", exif().getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
        assertEquals(before, size())
    }

    @Test
    fun theLocationIsWrittenAndReadBack() {
        writeLocation(GeoPoint(41.9028, 12.4964))

        val latLong = exif().latLong
        assertNotNull(latLong)
        assertEquals(41.9028, latLong!![0], 1e-4)
        assertEquals(12.4964, latLong[1], 1e-4)
    }

    @Test
    fun theLocationCanBeTakenOutAgain() {
        writeLocation(GeoPoint(-33.8688, 151.2093))
        write(ExifFields.GPS_TAGS, emptyMap())

        assertNull(exif().latLong)
        assertTrue(snapshot(ExifFields.GPS_TAGS).isEmpty())
    }

    @Test
    fun aSnapshotPutBackRestoresWhatWasThere() {
        write(ExifFields.DATE_TAGS, mapOf("DateTimeOriginal" to "2001:01:01 01:01:01", "OffsetTimeOriginal" to "+00:00"))
        val original = snapshot(ExifFields.DATE_TAGS)

        write(ExifFields.DATE_TAGS, mapOf("DateTimeOriginal" to "2030:12:31 12:00:00", "DateTimeDigitized" to "2030:12:31 12:00:00"))
        write(ExifFields.DATE_TAGS, original)

        assertEquals(original, snapshot(ExifFields.DATE_TAGS))
        assertEquals("2001:01:01 01:01:01", exif().getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertNull(exif().getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED))
    }

    @Test
    fun otherTagsAreLeftAlone() {
        write(ExifFields.DATE_TAGS, mapOf("DateTimeOriginal" to "2001:01:01 01:01:01"))
        exif().apply { setAttribute(ExifInterface.TAG_MAKE, "TestMaker"); saveAttributes() }

        write(ExifFields.DATE_TAGS, mapOf("DateTimeOriginal" to "2002:02:02 02:02:02"))

        assertEquals("TestMaker", exif().getAttribute(ExifInterface.TAG_MAKE))
    }
}
