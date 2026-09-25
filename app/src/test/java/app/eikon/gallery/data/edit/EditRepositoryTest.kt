package app.eikon.gallery.data.edit

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.EditDao
import app.eikon.gallery.data.db.EditRecipeEntity
import app.eikon.gallery.data.db.MediaDao
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.edit.Adjustments
import app.eikon.gallery.domain.edit.Crop
import app.eikon.gallery.domain.edit.EditAdaptation
import app.eikon.gallery.domain.edit.EditFilter
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.EditRecipeCodec
import app.eikon.gallery.domain.edit.Geometry
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving, pasting and reverting edits, on an in-memory stand-in for the database. */
class EditRepositoryTest {
    private val dao = FakeEditDao()
    private val repository = EditRepository(dao, unusedMediaDao(), Clock { 42L })

    private fun photo(id: Long, modifiedAt: Long = 7) = MediaItem(
        id, "p$id.jpg", "image/jpeg", isVideo = false, takenAt = 1, addedAt = 1, modifiedAt = modifiedAt, width = 400, height = 300,
        durationMs = 0, sizeBytes = 1, relativePath = null, bucketName = null, isFavorite = false,
    )

    private val warm = EditRecipe(adjustments = Adjustments(exposure = 0.5f, saturation = 0.2f), filter = EditFilter.WARM, filterAmount = 0.6f)
    private val cropped = EditRecipe(geometry = Geometry(quarterTurns = 1, crop = Crop(0.1f, 0.1f, 0.9f, 0.9f)))

    @Test
    fun savingStoresTheRecipeAsTextAlongsideTheFileTimeItWasMadeAgainst() = runTest {
        repository.save(photo(1, modifiedAt = 99), warm)

        val row = dao.rows.getValue(1)
        assertEquals(warm, EditRecipeCodec.decode(row.recipe))
        assertEquals(42L, row.updatedAt)
        assertEquals(99L, row.baseModifiedAt)
        assertEquals(warm, repository.recipe(1))
    }

    @Test
    fun aRecipeThatChangesNothingRemovesTheEditInsteadOfKeepingAnEmptyOne() = runTest {
        repository.save(photo(1), warm)
        repository.save(photo(1), EditRecipe.NONE)

        assertTrue(dao.rows.isEmpty())
        assertNull(repository.recipe(1))
    }

    @Test
    fun theTextsOfAllEditsAreThereForThumbnailsByPhotoId() = runTest {
        repository.save(photo(1), warm)
        repository.save(photo(2), cropped)

        val texts = repository.recipeTexts.first()
        assertEquals(setOf(1L, 2L), texts.keys)
        assertEquals(EditRecipeCodec.encode(warm), texts.getValue(1))
    }

    @Test
    fun revertingRemovesTheEditsOfThosePhotosOnly() = runTest {
        listOf(1L, 2L, 3L).forEach { repository.save(photo(it), warm) }

        repository.revert(listOf(1, 3))

        assertEquals(setOf(2L), dao.rows.keys)
    }

    @Test
    fun revertingAPhotoWithoutAnEditIsHarmless() = runTest {
        repository.save(photo(1), warm)
        repository.revert(listOf(5))
        assertEquals(setOf(1L), dao.rows.keys)
    }

    @Test
    fun revertingManyPhotosGoesInChunksNotOneHugeStatement() = runTest {
        val ids = (1L..1200L).toList()
        ids.forEach { dao.rows[it] = EditRecipeEntity(it, "x", 0, 0) }

        repository.revert(ids)

        assertTrue(dao.rows.isEmpty())
        assertTrue("largest delete: ${dao.largestDelete}", dao.largestDelete <= 500)
    }

    @Test
    fun editedAmongTellsWhichOfSomePhotosHaveAnEdit() = runTest {
        repository.save(photo(2), warm)
        repository.save(photo(4), warm)
        assertEquals(setOf(2L, 4L), repository.editedAmong(listOf(1, 2, 3, 4)))
        assertEquals(emptySet<Long>(), repository.editedAmong(listOf(1, 3)))
    }

    @Test
    fun pastingGivesEachPhotoTheLookWithoutDisturbingAnythingElse() = runTest {
        repository.paste(listOf(photo(1), photo(2)), warm)

        assertEquals(warm, repository.recipe(1))
        assertEquals(warm, repository.recipe(2))
    }

    @Test
    fun pastingKeepsThePhotosOwnCropAndTurnsAndTakesOnlyTheLook() = runTest {
        repository.save(photo(1), cropped)

        repository.paste(listOf(photo(1)), warm)

        val result = repository.recipe(1)!!
        assertEquals(cropped.geometry, result.geometry)
        assertEquals(warm.adjustments, result.adjustments)
        assertEquals(EditFilter.WARM, result.filter)
    }

    @Test
    fun theCropOfThePhotoTheLookWasCopiedFromNeverTravels() = runTest {
        val fromCroppedPhoto = warm.copy(geometry = cropped.geometry)

        repository.paste(listOf(photo(1)), fromCroppedPhoto)

        assertEquals(Geometry.NONE, repository.recipe(1)!!.geometry)
    }

    @Test
    fun pastingReplacesTheLookAPhotoAlreadyHad() = runTest {
        repository.save(photo(1), EditRecipe(adjustments = Adjustments(contrast = 0.9f)))

        repository.paste(listOf(photo(1)), warm)

        assertEquals(0f, repository.recipe(1)!!.adjustments.contrast, 0f)
        assertEquals(0.5f, repository.recipe(1)!!.adjustments.exposure, 0f)
    }

    @Test
    fun pastingANeutralLookOntoAPhotoThatOnlyHadALookRemovesItsEdit() = runTest {
        repository.save(photo(1), warm)

        repository.paste(listOf(photo(1)), EditRecipe.NONE)

        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun pastingGoesThroughTheAdaptationSeamSoAFutureVersionCanChangeTheLook() = runTest {
        val dimmer = EditAdaptation { recipe, _, _ -> recipe.copy(adjustments = recipe.adjustments.copy(exposure = 0.1f)) }

        repository.paste(listOf(photo(1)), warm, dimmer)

        assertEquals(0.1f, repository.recipe(1)!!.adjustments.exposure, 0f)
    }

    @Test
    fun pastingOntoManyPhotosStoresOneRowEach() = runTest {
        val photos = (1L..1200L).map { photo(it) }

        repository.paste(photos, warm)

        assertEquals(1200, dao.rows.size)
    }

    private fun unusedMediaDao(): MediaDao = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MediaDao::class.java)) { _, method, _ ->
        error("the edit repository should not have used ${method.name} here")
    } as MediaDao

    private class FakeEditDao : EditDao {
        val rows = LinkedHashMap<Long, EditRecipeEntity>()
        var largestDelete = 0
        private val changes = MutableStateFlow(0)

        override fun observeAll(): Flow<List<EditRecipeEntity>> = changes.map { rows.values.toList() }

        override suspend fun get(mediaId: Long) = rows[mediaId]

        override suspend fun upsert(entity: EditRecipeEntity) {
            rows[entity.mediaId] = entity
            changes.value++
        }

        override suspend fun upsertAll(entities: List<EditRecipeEntity>) = entities.forEach { upsert(it) }

        override suspend fun delete(ids: List<Long>) {
            largestDelete = maxOf(largestDelete, ids.size)
            ids.forEach { rows.remove(it) }
            changes.value++
        }

        override suspend fun get(ids: List<Long>) = ids.mapNotNull { rows[it] }
    }
}
