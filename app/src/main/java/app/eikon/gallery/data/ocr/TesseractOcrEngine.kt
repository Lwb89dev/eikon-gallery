package app.eikon.gallery.data.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * OCR with Tesseract's LSTM engine and the `tessdata_fast` English and Italian models bundled in the
 * APK (Apache-2.0, see NOTICE.md). The models are copied out of the assets once, because Tesseract
 * reads them from a real directory. Recognition is CPU-only and slow (seconds per photo), which is why
 * it only runs in the background analysis.
 */
@Singleton
class TesseractOcrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : OcrEngine {
    private val lock = Mutex()
    private var api: TessBaseAPI? = null

    override suspend fun recognize(bitmap: Bitmap): OcrResult = lock.withLock {
        withContext(Dispatchers.Default) {
            val engine = api ?: create().also { api = it }
            engine.setImage(bitmap)
            val text = engine.getUTF8Text().orEmpty()
            val confidence = engine.meanConfidence()
            engine.clear()
            OcrResult(text, confidence)
        }
    }

    override fun release() {
        api?.recycle()
        api = null
    }

    private fun create(): TessBaseAPI {
        val root = File(context.filesDir, "tesseract")
        copyModels(File(root, "tessdata"))
        val engine = TessBaseAPI()
        check(engine.init(root.absolutePath, LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)) { "Tesseract failed to initialize" }
        engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        return engine
    }

    /** Copies each bundled model into place unless an identical-size copy is already there. */
    private fun copyModels(directory: File) {
        directory.mkdirs()
        for (language in LANGUAGE_FILES) {
            val target = File(directory, language)
            val size = context.assets.openFd("tessdata/$language").use { it.length }
            if (target.length() == size) continue
            context.assets.open("tessdata/$language").use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }

    private companion object {
        const val LANGUAGES = "eng+ita"
        val LANGUAGE_FILES = listOf("eng.traineddata", "ita.traineddata")
    }
}
