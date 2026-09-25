package app.eikon.gallery.core.image

import android.graphics.Bitmap
import app.eikon.gallery.data.edit.withRecipe
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.EditRecipeCodec
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Draws an edit on a photo the image loader has just decoded, so the viewer shows the photo as edited. The file is never touched. */
class EditTransformation(private val recipe: EditRecipe) : Transformation() {
    override val cacheKey: String = "edit:" + EditRecipeCodec.encode(recipe)

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = withContext(Dispatchers.Default) { input.withRecipe(recipe) }
}
