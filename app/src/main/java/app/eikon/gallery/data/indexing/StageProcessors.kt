package app.eikon.gallery.data.indexing

import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.db.MediaGeoEntity
import app.eikon.gallery.data.embedding.ClipImageLoader
import app.eikon.gallery.data.embedding.EmbeddingRepository
import app.eikon.gallery.data.embedding.Embeddings
import app.eikon.gallery.data.embedding.ImageEncoderProvider
import app.eikon.gallery.data.faces.FaceImageLoader
import app.eikon.gallery.data.faces.FaceIndexerProvider
import app.eikon.gallery.data.faces.PeopleRepository
import app.eikon.gallery.data.metadata.PhotoLocationReader
import app.eikon.gallery.data.ocr.OcrEngine
import app.eikon.gallery.data.ocr.OcrImageLoader
import app.eikon.gallery.data.ocr.OcrTextFilter
import app.eikon.gallery.data.places.GazetteerProvider
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thrown by a stage that cannot work at all right now (a model that will not load), as opposed to failing
 * on one photo. The run stops that stage without blaming any photo, so nothing is marked failed for a
 * problem that is not the photo's.
 */
class StageUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

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


/** Works out what the photo shows, as a vector the search can compare with a typed phrase. */
class SemanticStageProcessor @Inject constructor(
    private val loader: ClipImageLoader,
    private val encoders: ImageEncoderProvider,
    private val repository: EmbeddingRepository,
) : StageProcessor {
    override suspend fun process(item: MediaEntity): StageOutcome = withContext(Dispatchers.Default) {
        val encoder = encoders.get()
        val vector = encoder.embed(loader.load(item.id))
        repository.save(item.id, Embeddings.quantize(vector))
        StageOutcome.DONE
    }

    override fun release() = encoders.close()
}

/** Finds the faces in the photo, describes each, and groups them into people. A photo without faces is skipped. */
class FaceStageProcessor @Inject constructor(
    private val loader: FaceImageLoader,
    private val indexers: FaceIndexerProvider,
    private val people: PeopleRepository,
) : StageProcessor {
    override suspend fun process(item: MediaEntity): StageOutcome = withContext(Dispatchers.Default) {
        val faces = indexers.get().describe(loader.load(item.id))
        people.saveFaces(item.id, faces)
        if (faces.isEmpty()) StageOutcome.SKIPPED else StageOutcome.DONE
    }

    override fun release() = indexers.close()
}
