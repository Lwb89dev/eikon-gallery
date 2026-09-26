package app.eikon.gallery.data.edit

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.mediastore.ShareEntry
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.edit.EditRecipe
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides what is handed to another app when photos are shared. An edit only exists inside eikon, so an edited photo is drawn to a temporary
 * JPEG (in the app's cache, without metadata) and that is shared; everything else is shared as the file it is. The original of an edited photo
 * is never what leaves the phone by accident, and is never changed.
 */
@Singleton
class EditedShare @Inject constructor(
    @ApplicationContext private val context: Context,
    private val edits: EditRepository,
    private val exporter: EditExporter,
    private val clock: Clock,
) {
    private val root: File get() = File(context.cacheDir, SharedFiles.FOLDER)

    suspend fun prepare(items: List<MediaItem>): List<ShareEntry> {
        val recipes = edits.recipes(items.filterNot { it.isVideo }.map { it.id })
        if (recipes.isEmpty()) return items.map { ShareEntry(it.uri, it.mimeType) }
        SharedFiles.sweep(root, clock.nowMillis())
        val batch = SharedFiles.newBatch(root, clock.nowMillis())
        return items.mapIndexed { index, item ->
            val recipe = recipes[item.id]?.takeUnless { it.isIdentity } ?: return@mapIndexed ShareEntry(item.uri, item.mimeType)
            ShareEntry(drawn(batch, index, item, recipe), "image/jpeg")
        }
    }

    private suspend fun drawn(batch: File, index: Int, item: MediaItem, recipe: EditRecipe): Uri {
        val folder = File(batch, index.toString()).apply { mkdirs() }
        val file = File(folder, SharedFiles.fileName(item.displayName))
        exporter.renderTo(file, item, recipe)
        return FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
    }

    private companion object {
        const val AUTHORITY_SUFFIX = ".shared"
    }
}
