package app.eikon.gallery.data.embedding

import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.indexing.StageHealth
import app.eikon.gallery.domain.PetKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What a photo is compared with to decide whether it shows a dog or a cat: the two pets and a set of other things it
 * might show instead. A photo counts as a dog (or cat) when that description is the most likely one of all of them
 * and at least [MIN_PROBABILITY] likely. On 1,000 COCO photos, of 24 clearly visible dogs 71% were found with 94%
 * of the results correct, and of 25 cats 92% were found with 85% correct; a cat was never taken for a dog or the
 * reverse (docs/ML.md). It works from the vectors already stored for search, so it costs no new analysis.
 */
object PetPrompts {
    const val MIN_PROBABILITY = 0.6f

    private val OTHERS = listOf(
        "una persona", "un paesaggio", "del cibo", "un'automobile", "un edificio", "una stanza", "un oggetto", "una strada",
        "un mezzo di trasporto", "un fiore", "uno schermo", "un documento", "un animale selvatico", "un uccello", "un cavallo",
        "un elefante", "una mucca",
    )

    /** Phrase list; the first entries are the pets, in the order of [PetKind]. */
    val PHRASES: List<String> = (listOf("un cane", "un gatto") + OTHERS).map { SemanticQuery.phrase(it) }

    fun index(kind: PetKind): Int = kind.ordinal
}

/** Finds the photos of dogs or cats among the photos analysed for search, and hands them to the list under a query id. */
@Singleton
class PetClassifier @Inject constructor(
    private val repository: EmbeddingRepository,
    private val textEncoders: TextEncoderProvider,
    private val health: StageHealth,
) {
    private val lock = Mutex()
    private var promptVectors: List<FloatArray>? = null

    /** The query id under which the photos of [kind] were stored, or null if there are none or the model is unavailable. */
    suspend fun find(kind: PetKind): Long? = withContext(Dispatchers.Default) {
        lock.withLock {
            val hits = try {
                repository.loadMatrix().classify(prompts(), PetPrompts.index(kind), PetPrompts.MIN_PROBABILITY).also { health.markWorking(IndexStage.EMBED) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                health.markUnavailable(IndexStage.EMBED)
                return@withLock null
            } catch (_: LinkageError) {
                health.markUnavailable(IndexStage.EMBED)
                return@withLock null
            }
            if (hits.isEmpty()) null else repository.storeHits(hits)
        }
    }

    private suspend fun prompts(): List<FloatArray> =
        promptVectors ?: PetPrompts.PHRASES.map { textEncoders.embed(it) }.also { promptVectors = it }
}
