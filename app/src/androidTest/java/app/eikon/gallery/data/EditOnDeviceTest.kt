package app.eikon.gallery.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.eikon.gallery.data.edit.BitmapPixelSource
import app.eikon.gallery.data.edit.toBitmap
import app.eikon.gallery.data.edit.toRgbImage
import app.eikon.gallery.data.edit.withRecipe
import app.eikon.gallery.domain.edit.Adjustments
import app.eikon.gallery.domain.edit.Crop
import app.eikon.gallery.domain.edit.EditFilter
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.EditRenderer
import app.eikon.gallery.domain.edit.Geometry
import app.eikon.gallery.domain.edit.GeometryMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Android side of editing, on a bundled public-domain sample photo (nothing here touches the user's photos or the app's data): the
 * platform's bitmaps in and out of the renderer, that drawing an edit never changes the picture it was drawn from, and that drawing in
 * bands, as the export does, gives the same picture as drawing it whole.
 */
@RunWith(AndroidJUnit4::class)
class EditOnDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    private fun photo(): Bitmap {
        val decoded = assets.open("embedding/photos/coffee.jpg").use { BitmapFactory.decodeStream(it) }
        return decoded.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun pixelsOf(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    private val heavy = EditRecipe(
        adjustments = Adjustments(exposure = 0.4f, contrast = 0.3f, saturation = 0.2f, sharpness = 0.5f, vignette = -0.3f),
        geometry = Geometry(quarterTurns = 1, straightenDegrees = 3f, crop = Crop(0.1f, 0.05f, 0.95f, 0.9f)),
        filter = EditFilter.CHROME,
        filterAmount = 0.7f,
    )

    @Test
    fun drawingAnEditNeverChangesThePictureItWasDrawnFrom() {
        val source = photo()
        val before = pixelsOf(source)

        val edited = source.withRecipe(heavy)

        assertTrue(pixelsOf(source).contentEquals(before))
        assertNotEquals(before.toList(), pixelsOf(edited).toList())
    }

    @Test
    fun aRecipeThatChangesNothingGivesBackThePictureItself() {
        val source = photo()
        assertSame(source, source.withRecipe(EditRecipe.NONE))
    }

    @Test
    fun theOutputHasTheSizeTheGeometryPromises() {
        val source = photo()
        val map = GeometryMap(source.width, source.height, heavy.geometry)

        val edited = source.withRecipe(heavy)

        val (width, height) = map.sizeWithin(maxOf(source.width, source.height))
        assertEquals(width, edited.width)
        assertEquals(height, edited.height)
    }

    @Test
    fun drawingInBandsGivesTheSamePictureAsDrawingItWhole() {
        val source = photo()
        val map = GeometryMap(source.width, source.height, heavy.geometry)
        val width = map.fullWidth
        val height = map.fullHeight
        val whole = EditRenderer.render(BitmapPixelSource(source), heavy, maxOf(width, height))

        val banded = IntArray(width * height)
        EditRenderer.renderBands(BitmapPixelSource(source), heavy, width, height, bandRows = 37) { top, rows, pixels ->
            pixels.copyInto(banded, top * width, 0, rows * width)
        }

        assertEquals(whole.width, width)
        assertEquals(whole.pixels.toList(), banded.toList())
    }

    @Test
    fun aBitmapSurvivesTheRoundTripThroughThePlainPictureUnchanged() {
        val source = photo()
        val back = source.toRgbImage().toBitmap()
        assertEquals(pixelsOf(source).toList(), pixelsOf(back).toList())
    }

    @Test
    fun bitmapPixelSourceReadsExactlyTheBoxItIsAskedFor() {
        val source = photo()
        val out = IntArray(5 * 4)
        BitmapPixelSource(source).read(3, 2, 5, 4, out)
        for (y in 0 until 4) for (x in 0 until 5) assertEquals(source.getPixel(3 + x, 2 + y), out[y * 5 + x])
    }
}
