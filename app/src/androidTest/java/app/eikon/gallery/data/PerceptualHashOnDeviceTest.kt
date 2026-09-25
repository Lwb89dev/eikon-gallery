package app.eikon.gallery.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.eikon.gallery.data.duplicates.PerceptualHash
import app.eikon.gallery.data.embedding.RgbImage
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The picture fingerprint on the phone, through the platform's own image decoding and scaling (the path the hash step uses): the same photo
 * at other sizes gets a fingerprint within the copy limit, and different photos are far apart. The photos are the public-domain
 * samples bundled with the tests; nothing here touches the user's photos or the app's data.
 */
@RunWith(AndroidJUnit4::class)
class PerceptualHashOnDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val names = listOf("cat", "astronaut", "coffee", "rocket")

    private fun decode(name: String): Bitmap = assets.open("embedding/photos/$name.jpg").use { BitmapFactory.decodeStream(it) }

    /** What the hash step does: reduce the photo to a small square with the platform's scaler, then fingerprint it. */
    private fun hashAt(bitmap: Bitmap, width: Int, height: Int): Long {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        return PerceptualHash.of(RgbImage(width, height, pixels))
    }

    @Test
    fun theSamePhotoAtOtherSizesIsAWithinTheCopyLimit() {
        for (name in names) {
            val bitmap = decode(name)
            val reference = hashAt(bitmap, 64, 64)
            for ((w, h) in listOf(bitmap.width to bitmap.height, 128 to 128, 48 to 48)) {
                val distance = PerceptualHash.distance(reference, hashAt(bitmap, w, h))
                assertTrue("$name at ${w}x$h: $distance bits", distance <= PerceptualHash.DUPLICATE_DISTANCE)
            }
        }
    }

    @Test
    fun differentPhotosAreFarApart() {
        val hashes = names.map { hashAt(decode(it), 64, 64) }
        for (a in hashes.indices) for (b in a + 1 until hashes.size) {
            assertTrue("${names[a]} vs ${names[b]}", PerceptualHash.distance(hashes[a], hashes[b]) > 14)
        }
    }
}
