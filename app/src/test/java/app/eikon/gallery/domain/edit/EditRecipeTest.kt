package app.eikon.gallery.domain.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditRecipeTest {
    private val busy = EditRecipe(
        adjustments = Adjustments(exposure = 0.3f, brightness = -0.1f, contrast = 0.2f, highlights = -0.4f, shadows = 0.35f, blackPoint = 0.05f, saturation = 0.15f, vibrance = 0.3f, temperature = -0.2f, tint = 0.1f, sharpness = 0.5f, vignette = 0.25f),
        geometry = Geometry(quarterTurns = 3, flipHorizontal = true, flipVertical = true, straightenDegrees = -3.5f, perspectiveVertical = 0.2f, perspectiveHorizontal = -0.1f, crop = Crop(0.1f, 0.15f, 0.9f, 0.95f)),
        filter = EditFilter.CHROME,
        filterAmount = 0.6f,
    )

    @Test
    fun aRecipeSurvivesTheTripToTextAndBack() {
        assertEquals(busy, EditRecipeCodec.decode(EditRecipeCodec.encode(busy)))
        assertEquals(EditRecipe.NONE, EditRecipeCodec.decode(EditRecipeCodec.encode(EditRecipe.NONE)))
    }

    @Test
    fun theTextOnlyMentionsWhatWasChanged() {
        val text = EditRecipeCodec.encode(EditRecipe(adjustments = Adjustments(exposure = 0.5f)))
        assertEquals(listOf("eikon-edit 1", "exposure=0.5"), text.lines())
        assertEquals("eikon-edit 1", EditRecipeCodec.encode(EditRecipe.NONE))
    }

    @Test
    fun aLaterVersionsUnknownToolsAreIgnoredButTheRestIsKept() {
        val text = "eikon-edit 2\nexposure=0.5\nhologram=9\nfilter=quantum\n"
        val recipe = EditRecipeCodec.decode(text)!!
        assertEquals(0.5f, recipe.adjustments.exposure, 0f)
        assertEquals(EditFilter.NONE, recipe.filter)
    }

    @Test
    fun textThatIsNotARecipeIsNoRecipe() {
        listOf("", "hello", "eikon-edit", "eikon-edit x", "other 1\nexposure=1", "exposure=1").forEach { assertNull("for '$it'", EditRecipeCodec.decode(it)) }
    }

    @Test
    fun brokenValuesFallBackToNeutralAndAreClamped() {
        val recipe = EditRecipeCodec.decode("eikon-edit 1\nexposure=abc\ncontrast=9\ncrop=1,2,3\nturns=-1\nstraighten=400")!!
        assertEquals(0f, recipe.adjustments.exposure, 0f)
        assertEquals(1f, recipe.adjustments.contrast, 0f)
        assertTrue(recipe.geometry.crop.isFull)
        assertEquals(3, recipe.geometry.quarterTurns)
        assertEquals(Geometry.MAX_STRAIGHTEN, recipe.geometry.straightenDegrees, 0f)
    }

    @Test
    fun aCropIsKeptInsideThePictureAndNeverEmpty() {
        val crop = Crop(-1f, 0.5f, 0.52f, 5f).clamped()
        assertEquals(0f, crop.left, 0f)
        assertTrue(crop.right - crop.left >= Crop.MIN_SIZE - 1e-6f)
        assertEquals(1f, crop.bottom, 0f)
    }

    @Test
    fun aRecipeIsTheIdentityOnlyWhenItChangesNothing() {
        assertTrue(EditRecipe.NONE.isIdentity)
        assertTrue(EditRecipe(filter = EditFilter.SEPIA, filterAmount = 0f).isIdentity)
        assertFalse(EditRecipe(filter = EditFilter.SEPIA).isIdentity)
        assertFalse(EditRecipe(geometry = Geometry(flipHorizontal = true)).isIdentity)
        assertFalse(EditRecipe(adjustments = Adjustments(tint = 0.1f)).isIdentity)
    }

    @Test
    fun pastingCarriesTheLookButNotTheCropOrTheTurns() {
        val pasted = busy.pasteable()
        assertEquals(busy.adjustments, pasted.adjustments)
        assertEquals(busy.filter, pasted.filter)
        assertTrue(pasted.geometry.isNeutral)
    }

    @Test
    fun theDefaultAdaptationChangesNothing() {
        assertEquals(busy, EditAdaptation.Same.adapt(busy, null, null))
        assertNotNull(EditAdaptation.Same)
    }

    // --- video trim -----------------------------------------------------------------------------

    @Test
    fun aTrimSurvivesTheTripToTextAndBack() {
        val trimmed = EditRecipe(trim = VideoTrim(1_500L, 9_000L))
        assertEquals(trimmed, EditRecipeCodec.decode(EditRecipeCodec.encode(trimmed)))
        assertEquals(listOf("eikon-edit 1", "trimStart=1500", "trimEnd=9000"), EditRecipeCodec.encode(trimmed).lines())
    }

    @Test
    fun trimmingOnlyTheStartLeavesTheEndOpen() {
        val trimmed = EditRecipe(trim = VideoTrim(startMs = 2_000L))
        assertNull(trimmed.trim.endMs)
        val decoded = EditRecipeCodec.decode(EditRecipeCodec.encode(trimmed))!!
        assertEquals(2_000L, decoded.trim.startMs)
        assertNull("open end is not turned into some fixed number", decoded.trim.endMs)
    }

    @Test
    fun aRecipeWithOnlyATrimIsNotTheIdentity() {
        assertFalse(EditRecipe(trim = VideoTrim(endMs = 5_000L)).isIdentity)
        assertTrue(EditRecipe(trim = VideoTrim.NONE).isIdentity)
    }

    @Test
    fun pastingDropsTheTrimTooItBelongsToOneVideosLength() {
        val recipe = EditRecipe(adjustments = Adjustments(exposure = 0.2f), trim = VideoTrim(1_000L, 8_000L))
        assertTrue(recipe.pasteable().trim.isNeutral)
    }

    @Test
    fun aTrimCannotStartBeforeZeroOrLeaveLessThanTheMinimumDuration() {
        assertEquals(0L, VideoTrim(-500L, 100L).clamped().startMs)
        assertEquals(VideoTrim.MIN_DURATION_MS, VideoTrim(0L, 10L).clamped().endMs)
        assertEquals(3_000L + VideoTrim.MIN_DURATION_MS, VideoTrim(3_000L, 3_010L).clamped().endMs)
        assertNull("clamping never invents an end where there was none", VideoTrim(3_000L).clamped().endMs)
    }
}
