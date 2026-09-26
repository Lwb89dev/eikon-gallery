package app.eikon.gallery.data.embedding

import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.indexing.StageHealth
import app.eikon.gallery.data.settings.SettingsRepository
import app.eikon.gallery.domain.search.SearchSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a search phrase is worded for the text model. */
object SemanticQuery {
    /**
     * Words alone ("cane") are ambiguous to the model; inside a phrase they read like a photo caption, which
     * is what it was trained on. On 20 object categories this Italian template raised precision of the first
     * ten results from 65% to 73% for Italian words and left English words unchanged (74% to 73%).
     */
    fun phrase(words: String): String = "una foto di $words"
}

/**
 * Finds photos by what they show. It embeds the typed words, compares them with every stored photo
 * vector, and writes the best matches to the `search_hit` table that the list query then reads.
 * Nothing here needs the network: both models are inside the app.
 */
@Singleton
class SemanticSearchService @Inject constructor(
    private val repository: EmbeddingRepository,
    private val textEncoders: TextEncoderProvider,
    private val settings: SettingsRepository,
    private val health: StageHealth,
) {
    /**
     * Returns [spec] marked as semantic when the free words found photos by what they show. When the feature
     * is off, there are no free words, or nothing matched, the spec comes back unchanged and the search
     * behaves exactly as before.
     */
    suspend fun prepare(spec: SearchSpec): SearchSpec {
        val words = spec.semanticText ?: return spec
        if (settings.state.value?.analysis?.semantic != true) return spec
        val hits = searchOrNull(words)?.takeIf { it.isNotEmpty() } ?: return spec
        return spec.copy(semanticQuery = repository.storeHits(hits))
    }

    /** A model that cannot load must not take the search screen down: the search falls back to text and the user is told. */
    private suspend fun searchOrNull(words: String): List<ScoredMedia>? = try {
        search(words).also { health.markWorking(IndexStage.EMBED) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        health.markUnavailable(IndexStage.EMBED)
        null
    } catch (_: LinkageError) {
        health.markUnavailable(IndexStage.EMBED)
        null
    }

    /** Photos matching [words], best first. */
    suspend fun search(words: String): List<ScoredMedia> = withContext(Dispatchers.Default) {
        val photos = repository.matrix()
        if (photos.size == 0) return@withContext emptyList()
        photos.search(textEncoders.embed(SemanticQuery.phrase(words)))
    }
}
