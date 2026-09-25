package app.eikon.gallery.data.indexing

import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.db.MediaGeoEntity
import app.eikon.gallery.data.metadata.PhotoLocationReader
import app.eikon.gallery.data.ocr.OcrEngine
import app.eikon.gallery.data.ocr.OcrImageLoader
import app.eikon.gallery.data.ocr.OcrTextFilter
import app.eikon.gallery.data.places.GazetteerProvider
import javax.inject.Inject

/** What a stage did with one photo. A stage that cannot finish throws instead. */
enum class StageOutcome {
    /** Something was extracted and stored. */
    DONE,

    /** The photo has nothing to extract (no GPS, no text). */
    SKIPPED,
}

/** One step of the analysis pipeline. Implementations must be safe to call for any photo, in any order. */
interface StageProcessor {
    suspend fun process(item: MediaEntity): StageOutcome

    /** Called when a run ends, to free memory the stage holds (models, caches). */
    fun release() = Unit
}

/** Finds where the photo was taken and resolves it, offline, to the nearest known city. */
class GeoStageProcessor @Inject constructor(
    private val locations: PhotoLocationReader,
    private val gazetteers: GazetteerProvider,
    private val repository: IndexingRepository,
) : StageProcessor {
    override suspend fun process(item: MediaEntity): StageOutcome {
        val point = locations.read(item.id) ?: return StageOutcome.SKIPPED
        val city = gazetteers.get().nearest(point.latitude, point.longitude)
        repository.saveGeo(
            MediaGeoEntity(item.id, point.latitude, point.longitude, city?.id, city?.countryCode, city?.regionKey),
        )
        return StageOutcome.DONE
    }
}

/** Reads the text in the photo and stores it in the search index if it looks like real text. */
class OcrStageProcessor @Inject constructor(
    private val loader: OcrImageLoader,
    private val engine: OcrEngine,
    private val repository: IndexingRepository,
) : StageProcessor {
    override suspend fun process(item: MediaEntity): StageOutcome {
        val bitmap = loader.load(item.id)
        try {
            val result = engine.recognize(bitmap)
            val text = OcrTextFilter.clean(result.text, result.confidence) ?: return StageOutcome.SKIPPED
            repository.saveText(item.id, text)
            return StageOutcome.DONE
        } finally {
            bitmap.recycle()
        }
    }

    override fun release() = engine.release()
}

