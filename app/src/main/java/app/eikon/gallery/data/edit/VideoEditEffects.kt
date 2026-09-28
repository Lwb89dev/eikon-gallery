package app.eikon.gallery.data.edit

import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.SingleColorLut
import app.eikon.gallery.domain.edit.EditFilter
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.GeometryMap
import app.eikon.gallery.domain.edit.VideoColorLut
import app.eikon.gallery.domain.edit.VideoTrim
import androidx.media3.common.MediaItem as PlayerMediaItem

/**
 * Turns the color and geometry of an [EditRecipe] into Media3 GPU effects, for showing (and, with `Transformer`, exporting) a video with
 * the same look a photo would get from the same recipe. The trim ([EditRecipe.trim]) is not here: it is a clipping range on the
 * `MediaItem`, not a per-pixel effect.
 *
 * Two tools a photo has do not apply to video yet, because both need more than one pixel's own color, which a lookup table cannot express
 * and which nothing here has a GPU pass for: **sharpening** and the **vignette**. **Perspective correction** is left out too, because it is
 * a projective warp with no ready-made Media3 effect, and hand-writing one is exactly the kind of GPU code that cannot be checked without a
 * device. `EditTools` hides the controls for all three while editing a video, so a stored video recipe never asks for them in the first
 * place. Straighten, quarter turns, flip and crop are plain affine transforms, which [ScaleAndRotateTransformation] and [Crop] (Media3's
 * own, already-tested effects) do support at any angle.
 *
 * **Not run on a device.** The rotation direction (Media3's own sign convention against [app.eikon.gallery.domain.edit.Geometry]'s "positive
 * turns clockwise") and the exact meaning of [Crop]'s four numbers are this file's own best reading of the public API, not something a
 * device has confirmed; a straighten or a crop that looks mirrored or turned the wrong way is the first thing to check.
 */
@OptIn(UnstableApi::class)
object VideoEditEffects {
    /** Effects for [recipe], in the order a `Player` or a `Transformer` should apply them; empty for a recipe with no color or (supported) geometry change. */
    fun forRecipe(recipe: EditRecipe, sourceWidth: Int, sourceHeight: Int): List<Effect> = buildList {
        addAll(geometryEffects(recipe, sourceWidth, sourceHeight))
        colorEffect(recipe)?.let { add(it) }
    }

    /** [trim] as what a `Player` or `Transformer` understands: a range of the file to play or render, nothing outside it ever decoded. */
    fun clippingConfigOf(trim: VideoTrim): PlayerMediaItem.ClippingConfiguration {
        if (trim.isNeutral) return PlayerMediaItem.ClippingConfiguration.UNSET
        val builder = PlayerMediaItem.ClippingConfiguration.Builder().setStartPositionMs(trim.startMs)
        trim.endMs?.let { builder.setEndPositionMs(it) }
        return builder.build()
    }

    private fun colorEffect(recipe: EditRecipe): Effect? {
        if (recipe.adjustments.isNeutral && recipe.filter == EditFilter.NONE) return null
        val cube = VideoColorLut.cube(recipe.adjustments, recipe.filter, recipe.filterAmount)
        return SingleColorLut.createFromCube(cube)
    }

    private fun geometryEffects(recipe: EditRecipe, sourceWidth: Int, sourceHeight: Int): List<Effect> {
        val g = recipe.geometry
        if (g.isNeutral) return emptyList()
        // Perspective needs a source width/height to weigh against the rotation for the fill zoom; zeroing it here is what limits this
        // to the affine part of the geometry (see the class doc).
        val flat = g.copy(perspectiveHorizontal = 0f, perspectiveVertical = 0f)
        val zoom = GeometryMap(sourceWidth, sourceHeight, flat).fillZoom.toFloat()
        val totalDegreesClockwise = flat.quarterTurns * QUARTER_TURN_DEGREES + flat.straightenDegrees
        val effects = mutableListOf<Effect>()
        if (totalDegreesClockwise != 0f || flat.flipHorizontal || flat.flipVertical || zoom != 1f) {
            val flipX = if (flat.flipHorizontal) -1f else 1f
            val flipY = if (flat.flipVertical) -1f else 1f
            effects += ScaleAndRotateTransformation.Builder()
                .setScale(flipX * zoom, flipY * zoom)
                .setRotationDegrees(-totalDegreesClockwise) // Media3's own convention is understood to be counter-clockwise-positive; see the class doc.
                .build()
        }
        if (!flat.crop.isFull) {
            val crop = flat.crop
            effects += Crop(crop.left * 2f - 1f, 1f - crop.top * 2f, crop.right * 2f - 1f, 1f - crop.bottom * 2f)
        }
        return effects
    }

    private const val QUARTER_TURN_DEGREES = 90f
}
