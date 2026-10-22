package app.eikon.gallery.feature.viewer

import androidx.lifecycle.ViewModel
import app.eikon.gallery.data.indexing.IndexingRepository
import app.eikon.gallery.data.ocr.OcrWord
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** What the viewer itself needs from the database, on demand, for whichever photo is open; not the photo list, which the caller already owns. */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val indexing: IndexingRepository,
) : ViewModel() {
    /** The words OCR found in this photo and where they sit, so they can be selected where they are actually written; empty for a photo with none (or not yet analysed). */
    suspend fun ocrWords(mediaId: Long): List<OcrWord> =
        indexing.ocrWords(mediaId).map { OcrWord(it.text, it.line, it.left, it.top, it.right, it.bottom) }
}
