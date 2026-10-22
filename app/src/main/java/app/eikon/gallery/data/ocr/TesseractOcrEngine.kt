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
            try {
                engine.setImage(bitmap)
                val text = engine.getUTF8Text().orEmpty()
                OcrResult(text, engine.meanConfidence(), words(engine, bitmap.width, bitmap.height))
            } finally {
                // Also after a failure: what the engine kept of this picture must not be read as part of the next one.
                engine.clear()
            }
        }
    }

    /**
     * The words [getUTF8Text] just read, each boxed, by walking the same result word by word. Must run after
     * [TessBaseAPI.getUTF8Text] (or another call that recognizes the page), which is what [recognize] above does;
     * an iterator asked for before that would simply find nothing.
     */
    private fun words(engine: TessBaseAPI, width: Int, height: Int): List<OcrWord> {
        if (width <= 0 || height <= 0) return emptyList()
        val iterator = engine.resultIterator ?: return emptyList()
        return try {
            val words = mutableListOf<OcrWord>()
            iterator.begin()
            var line = -1
            do {
                if (iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) line++
                val text = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                val box = iterator.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (text.isNotEmpty()) {
                    words += OcrWord(
                        text = text,
                        line = line,
                        left = (box.left.toFloat() / width).coerceIn(0f, 1f),
                        top = (box.top.toFloat() / height).coerceIn(0f, 1f),
                        right = (box.right.toFloat() / width).coerceIn(0f, 1f),
                        bottom = (box.bottom.toFloat() / height).coerceIn(0f, 1f),
                    )
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            words
        } finally {
            iterator.delete()
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
