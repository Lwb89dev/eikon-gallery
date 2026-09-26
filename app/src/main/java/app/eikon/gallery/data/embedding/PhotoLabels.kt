package app.eikon.gallery.data.embedding

import app.eikon.gallery.data.db.IndexDao
import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.indexing.StageHealth
import app.eikon.gallery.domain.PetKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Everyday subjects a photo may show. Each is described to the image model by a phrase ([prompt]) and to the user by a string (`label_*`, chosen in the info panel). It is a short list on purpose: the model is
 * asked which of these a photo is *most* like, so the list decides what can be said; it is not an inventory of what is in the picture.
 */
enum class PhotoSubject(val prompt: String, val offered: Boolean = true) {
    LANDSCAPE("un paesaggio"), BEACH("una spiaggia"), SEA("il mare"), MOUNTAIN("una montagna"), SUNSET("un tramonto"), SNOW("la neve"), CITY("una città"), BUILDING("un edificio"),
    STREET("una strada"), WOODS("un bosco"), FLOWER("un fiore"), SKY("il cielo"), PERSON("una persona"), GROUP("un gruppo di persone"), FOOD("del cibo"), DESSERT("un dolce"),
    DRINK("una bevanda"), CAR("un'automobile"), BICYCLE("una bicicletta"), AIRPLANE("un aereo"), BOAT("una barca"), BIRD("un uccello"), HORSE("un cavallo"),
    INTERIOR("una stanza"), NIGHT("una notte buia"), PARTY("una festa"), SPORT("un evento sportivo"), ANIMAL("un animale"),

    /** Dogs and cats are described to the model so a pet is not mistaken for something else, but they are reported as pets, never as a subject. */
    DOG("un cane", offered = false), CAT("un gatto", offered = false),
    ;

    companion object {
        val PHRASES: List<String> = entries.map { SemanticQuery.phrase(it.prompt) }
    }
}

/** What the analysis can say about one photo: the subjects it looks most like, and any pet in it. */
data class PhotoLabels(val subjects: List<PhotoSubject>, val pets: List<PetKind>)

/**
 * The arithmetic of [PhotoLabeler], apart from the model so it can be tested. A photo's vector is compared with the phrase vectors and the similarities go through a softmax at CLIP's usual
 * scale (100), exactly as in [EmbeddingMatrix.classify], so a subject's probability does not depend on the rest of the library.
 */
object LabelScoring {
    /**
     * Subjects are offered only when the likeliest one is at least [TOP_MIN] likely (else the photo is not like anything on the list closely enough to say), and then it and the
     * next few that are at least [OTHER_MIN] likely. If the likeliest description is a pet, nothing else is offered: the pet row says it.
     */
    const val TOP_MIN = 0.25f
    const val OTHER_MIN = 0.15f
    const val MAX_SUBJECTS = 3

    /** Probability of each phrase for the photo whose stored vector is [vector]. */
    fun probabilities(vector: ByteArray, phrases: List<FloatArray>): FloatArray {
        val logits = FloatArray(phrases.size) { LOGIT_SCALE * Embeddings.similarity(phrases[it], vector, 0) }
        val top = logits.max()
        val exps = DoubleArray(logits.size) { exp((logits[it] - top).toDouble()) }
        val sum = exps.sum()
        return FloatArray(logits.size) { (exps[it] / sum).toFloat() }
    }

    fun subjects(probabilities: FloatArray): List<PhotoSubject> {
        val ranked = probabilities.indices.sortedByDescending { probabilities[it] }
        val best = ranked.firstOrNull() ?: return emptyList()
        if (!PhotoSubject.entries[best].offered || probabilities[best] < TOP_MIN) return emptyList()
        return ranked.map { PhotoSubject.entries[it] }.filter { it.offered && probabilities[it.ordinal] >= OTHER_MIN }.take(MAX_SUBJECTS)
    }

    /** The pets in the photo, by the same rule as the dogs and cats collections: the likeliest description of all, and at least [PetPrompts.MIN_PROBABILITY] likely. */
    fun pets(vector: ByteArray, petPhrases: List<FloatArray>): List<PetKind> {
        val probabilities = probabilities(vector, petPhrases)
        val best = probabilities.indices.maxByOrNull { probabilities[it] } ?: return emptyList()
        val kind = PetKind.entries.firstOrNull { PetPrompts.index(it) == best } ?: return emptyList()
        return if (probabilities[best] >= PetPrompts.MIN_PROBABILITY) listOf(kind) else emptyList()
    }

    private const val LOGIT_SCALE = 100f
}

/**
 * Says what a photo probably shows, for the info panel, from the vector stored for search: no new analysis, and only for photos the analysis has already looked at (a photo
 * without a vector has no labels rather than made-up ones). The text model is loaded on demand and freed shortly after, like for Search.
 */
@Singleton
class PhotoLabeler @Inject constructor(
    private val dao: IndexDao,
    private val textEncoders: TextEncoderProvider,
    private val health: StageHealth,
) {
    private val lock = Mutex()
    private var subjectPhrases: List<FloatArray>? = null
    private var petPhrases: List<FloatArray>? = null

    /** The labels of [mediaId], or null if it has no stored vector or the model cannot be loaded. */
    suspend fun of(mediaId: Long): PhotoLabels? {
        val vector = dao.embedding(mediaId, EMBEDDING_MODEL_ID) ?: return null
        return withContext(Dispatchers.Default) { lock.withLock { label(vector) } }
    }

    private suspend fun label(vector: ByteArray): PhotoLabels? = try {
        val subjects = LabelScoring.subjects(LabelScoring.probabilities(vector, subjects()))
        val pets = LabelScoring.pets(vector, pets())
        health.markWorking(IndexStage.EMBED)
        PhotoLabels(subjects, pets)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        health.markUnavailable(IndexStage.EMBED)
        null
    } catch (_: LinkageError) {
        health.markUnavailable(IndexStage.EMBED)
        null
    }

    private suspend fun subjects(): List<FloatArray> = subjectPhrases ?: PhotoSubject.PHRASES.map { textEncoders.embed(it) }.also { subjectPhrases = it }

    private suspend fun pets(): List<FloatArray> = petPhrases ?: PetPrompts.PHRASES.map { textEncoders.embed(it) }.also { petPhrases = it }
}
