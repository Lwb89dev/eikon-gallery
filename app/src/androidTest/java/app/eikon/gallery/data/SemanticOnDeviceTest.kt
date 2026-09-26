package app.eikon.gallery.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.eikon.gallery.data.embedding.AssetModelStore
import app.eikon.gallery.data.embedding.ClipImageEncoder
import app.eikon.gallery.data.embedding.ClipImagePreprocessor
import app.eikon.gallery.data.embedding.ClipTextEncoder
import app.eikon.gallery.data.embedding.EmbeddingMatrix
import app.eikon.gallery.data.embedding.Embeddings
import app.eikon.gallery.data.embedding.IMAGE_MODEL
import app.eikon.gallery.data.embedding.RgbImage
import app.eikon.gallery.data.embedding.SemanticCutoff
import app.eikon.gallery.data.embedding.SemanticQuery
import kotlin.math.abs
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real models, read out of the installed APK and run by ONNX Runtime's Android build on the phone's
 * processor: the same code path the background analysis and the search use. It also reports how long each
 * step takes, which is the one number the JVM cannot tell (see docs/ML.md). Nothing here touches the
 * user's photos or the app's data.
 */
@RunWith(AndroidJUnit4::class)
class SemanticOnDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val store = AssetModelStore(instrumentation.targetContext)

    @Test
    fun theModelsLoadFromTheApkAndAPhraseFindsTheRightPhoto() {
        var image: ClipImageEncoder? = null
        var text: ClipTextEncoder? = null
        val loadImageMs = measureTimeMillis { image = ClipImageEncoder(store.map(IMAGE_MODEL), threads = 2) }
        val loadTextMs = measureTimeMillis { text = ClipTextEncoder.create(store) }
        try {
            val photos = PHOTOS.map { photo(it) }
            val vectors = ArrayList<FloatArray>()
            val embedMs = measureTimeMillis { photos.forEach { vectors += image!!.embed(it) } } / photos.size
            val matrix = EmbeddingMatrix.Builder(photos.size).also { b -> vectors.forEachIndexed { i, v -> b.add(i + 1L, Embeddings.quantize(v)) } }.build()

            var queryMs = 0L
            for ((words, expected) in EXPECTED) {
                var best = -1L
                queryMs += measureTimeMillis { best = bestPhotoFor(matrix, text!!, words) }
                assertEquals("best photo for '$words'", expected, best)
            }
            assertTrue("vectors must be unit length", vectors.all { abs(it.sumOf { x -> (x * x).toDouble() } - 1.0) < 1e-3 })
            report(loadImageMs, loadTextMs, embedMs, queryMs / EXPECTED.size)
        } finally {
            image?.close()
            text?.close()
        }
    }

    private fun bestPhotoFor(matrix: EmbeddingMatrix, text: ClipTextEncoder, words: String): Long =
        matrix.search(text.embed(SemanticQuery.phrase(words)), SemanticCutoff(floor = 0f, margin = 1f)).first().mediaId

    private fun report(loadImageMs: Long, loadTextMs: Long, embedMs: Long, queryMs: Long) {
        val line = "image model loaded in $loadImageMs ms, text model in $loadTextMs ms; " +
            "one photo embedded in $embedMs ms; one search phrase in $queryMs ms"
        Log.i(TAG, line)
        instrumentation.sendStatus(0, Bundle().apply { putString("eikon-semantic", line) })
    }

    /** A bundled test photo prepared as the app prepares a library photo: shorter side 224, central square. */
    private fun photo(name: String): RgbImage {
        val source = instrumentation.context.assets.open("embedding/photos/$name.jpg").use { BitmapFactory.decodeStream(it) }
        val (width, height) = ClipImagePreprocessor.decodeSize(source.width, source.height)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        return RgbImage(width, height, pixels).centerCrop(ClipImagePreprocessor.SIZE)
    }

    private companion object {
        const val TAG = "eikon-semantic"
        val PHOTOS = listOf("cat", "astronaut", "coffee", "rocket")
        val EXPECTED = mapOf("gatto" to 1L, "cat" to 1L, "astronauta" to 2L, "tazza di caffè" to 3L, "rocket launch" to 4L, "razzo che decolla" to 4L)
    }
}
