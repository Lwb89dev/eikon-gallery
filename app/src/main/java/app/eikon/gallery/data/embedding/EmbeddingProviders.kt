package app.eikon.gallery.data.embedding

import app.eikon.gallery.core.di.ApplicationScope
import app.eikon.gallery.data.indexing.StageUnavailableException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

/** Owns the image model for the length of an analysis run; loading it is slow, so it is loaded once per run. */
@Singleton
class ImageEncoderProvider @Inject constructor(
    private val store: ModelStore,
) {
    private var encoder: ImageEmbedder? = null

    /** Throws [StageUnavailableException] if the model cannot be loaded, so the photo is not blamed for it. */
    @Synchronized
    fun get(): ImageEmbedder {
        encoder?.let { return it }
        return try {
            ClipImageEncoder(store.map(IMAGE_MODEL)).also { encoder = it }
        } catch (e: Exception) {
            throw StageUnavailableException("The image model could not be loaded", e)
        } catch (e: LinkageError) {
            throw StageUnavailableException("The model runtime could not be loaded", e)
        }
    }

    @Synchronized
    fun close() {
        encoder?.close()
        encoder = null
    }
}

/** Owns the text model used by Search: kept loaded while the user is searching, freed shortly after. */
@Singleton
class TextEncoderProvider @Inject constructor(
    store: ModelStore,
    @ApplicationScope scope: CoroutineScope,
) {
    private val cache = IdleCache(scope, IDLE_MILLIS) { ClipTextEncoder.create(store) }

    suspend fun embed(text: String): FloatArray = cache.use { it.embed(text) }

    private companion object {
        const val IDLE_MILLIS = 60_000L
    }
}
