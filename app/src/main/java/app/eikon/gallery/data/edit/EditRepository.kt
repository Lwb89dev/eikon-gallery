package app.eikon.gallery.data.edit

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.EditDao
import app.eikon.gallery.data.db.EditRecipeEntity
import app.eikon.gallery.data.db.MediaDao
import app.eikon.gallery.data.db.toDomain
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.edit.EditAdaptation
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.EditRecipeCodec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Edits: which photos have one and what it is. An edit never touches a photo's file: it is a small piece of text kept here, drawn over the
 * original whenever the photo is shown, and removed to go back to the original.
 */
@Singleton
class EditRepository @Inject constructor(
    private val dao: EditDao,
    private val media: MediaDao,
    private val clock: Clock,
) {
    /**
     * Every edit as its stored text, by photo id. Kept as text so the many thumbnails of a grid can use it as a cache key without decoding it
     * again; whoever draws an edit decodes it with [EditRecipeCodec].
     */
    val recipeTexts: Flow<Map<Long, String>> = dao.observeAll().map { rows -> rows.associate { it.mediaId to it.recipe } }

    suspend fun mediaItem(id: Long): MediaItem? = media.byId(id)?.toDomain()

    suspend fun recipe(mediaId: Long): EditRecipe? = dao.get(mediaId)?.recipe?.let(EditRecipeCodec::decode)

    /** Stores [recipe] as the edit of [item]; a recipe that changes nothing removes the edit instead, so "edited" always means something. */
    suspend fun save(item: MediaItem, recipe: EditRecipe) {
        if (recipe.isIdentity) return revert(listOf(item.id))
        dao.upsert(EditRecipeEntity(item.id, EditRecipeCodec.encode(recipe), clock.nowMillis(), item.modifiedAt))
    }

    /** The edits among [ids], by photo id (photos without an edit are not in the map). */
    suspend fun recipes(ids: Collection<Long>): Map<Long, EditRecipe> = ids.chunked(CHUNK).flatMap { dao.get(it) }
        .mapNotNull { row -> EditRecipeCodec.decode(row.recipe)?.let { row.mediaId to it } }.toMap()

    /** Which of [ids] have an edit. */
    suspend fun editedAmong(ids: Collection<Long>): Set<Long> = ids.chunked(CHUNK).flatMap { dao.get(it) }.mapTo(HashSet()) { it.mediaId }

    /** Back to the original: the photos lose their edit. The files were never changed, so there is nothing to restore. */
    suspend fun revert(ids: Collection<Long>) {
        ids.chunked(CHUNK).forEach { dao.delete(it) }
    }

    /**
     * "Paste edits": each of [targets] takes the look of [recipe] (adjustments and filter) and keeps its own crop, turns and straightening. What
     * happens to the recipe on the way to another photo is decided by [adaptation]; today it is the same recipe.
     */
    suspend fun paste(targets: Collection<MediaItem>, recipe: EditRecipe, adaptation: EditAdaptation = EditAdaptation.Same) {
        val existing = targets.map { it.id }.chunked(CHUNK).flatMap { dao.get(it) }.associate { it.mediaId to EditRecipeCodec.decode(it.recipe) }
        val rows = targets.mapNotNull { item ->
            val own = existing[item.id]
            val adapted = adaptation.adapt(recipe.pasteable(), null, null)
            val merged = adapted.copy(geometry = own?.geometry ?: adapted.geometry)
            merged.takeUnless { it.isIdentity }?.let { EditRecipeEntity(item.id, EditRecipeCodec.encode(it), clock.nowMillis(), item.modifiedAt) }
        }
        rows.chunked(CHUNK).forEach { dao.upsertAll(it) }
        val emptied = targets.map { it.id } - rows.map { it.mediaId }.toSet()
        emptied.chunked(CHUNK).forEach { dao.delete(it) }
    }

    private companion object {
        const val CHUNK = 500
    }
}
