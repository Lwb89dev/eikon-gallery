package app.eikon.gallery.data

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.eikon.gallery.data.embedding.AssetModelStore
import app.eikon.gallery.data.embedding.RgbImage
import app.eikon.gallery.data.faces.FACE_DETECTOR_MODEL
import app.eikon.gallery.data.faces.FACE_EMBEDDER_MODEL
import app.eikon.gallery.data.faces.FaceAligner
import app.eikon.gallery.data.faces.SFaceEmbedder
import app.eikon.gallery.data.faces.YuNetFaceDetector
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The face models, read out of the installed APK and run on the phone's processor: the astronaut's face is
 * found, aligned and described, and nothing is found in a photo of a cat. It reports the time each step takes,
 * which is the number the JVM cannot tell (docs/ML.md). Nothing here touches the user's photos or app data.
 */
@RunWith(AndroidJUnit4::class)
class FacesOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = AssetModelStore(instrumentation.targetContext)

    @Test
    fun theFaceModelsLoadFromTheApkAndFindDescribeAndTellApartFaces() {
        var detector: YuNetFaceDetector? = null
        var embedder: SFaceEmbedder? = null
        val loadMs = measureTimeMillis {
            detector = YuNetFaceDetector(store.map(FACE_DETECTOR_MODEL))
            embedder = SFaceEmbedder(store.map(FACE_EMBEDDER_MODEL))
        }
        try {
            val astronaut = photo("astronaut")
            var faces = emptyList<app.eikon.gallery.data.faces.DetectedFace>()
            val detectMs = measureTimeMillis { faces = detector!!.detect(astronaut) }
            assertEquals(1, faces.size)
            assertTrue("no face expected in the cat photo", detector!!.detect(photo("cat")).isEmpty())

            var vector = FloatArray(0)
            val embedMs = measureTimeMillis { vector = embedder!!.embed(FaceAligner.crop(astronaut, faces.single().landmarks)) }
            assertEquals(128, vector.size)
            assertEquals(1.0, vector.sumOf { (it * it).toDouble() }, 1e-3)

            val line = "face models loaded in $loadMs ms; one 320x320 photo scanned in $detectMs ms; one face described in $embedMs ms"
            Log.i(TAG, line)
            instrumentation.sendStatus(0, Bundle().apply { putString("eikon-faces", line) })
        } finally {
            detector?.close()
            embedder?.close()
        }
    }

    private fun photo(name: String): RgbImage {
        val bitmap = instrumentation.context.assets.open("embedding/photos/$name.jpg").use { BitmapFactory.decodeStream(it) }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return RgbImage(bitmap.width, bitmap.height, pixels)
    }

    private companion object {
        const val TAG = "eikon-faces"
    }
}
