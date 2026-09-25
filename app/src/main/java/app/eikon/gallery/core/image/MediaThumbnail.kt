package app.eikon.gallery.core.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import app.eikon.gallery.data.edit.withRecipe
import app.eikon.gallery.domain.edit.EditRecipeCodec
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.mediaContentUri
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import android.util.Size as AndroidSize

/**
 * Model for a grid thumbnail. [modifiedAt] is part of the cache key so an edited photo does not keep
 * showing its old thumbnail.
 */
@Immutable
data class MediaThumbnailData(val uri: Uri, val modifiedAt: Long, val recipe: String? = null)

class MediaThumbnailKeyer : Keyer<MediaThumbnailData> {
    override fun key(data: MediaThumbnailData, options: Options): String =
        "thumb:${data.uri}:${data.modifiedAt}:${data.recipe.orEmpty().hashCode()}"
}

/**
 * Loads thumbnails through ContentResolver.loadThumbnail, i.e. from the system's own thumbnail cache
 * at roughly the requested size. Unlike decoding the file, the grid therefore never touches a
 * full-resolution image, which is what keeps scrolling smooth on 50k-item libraries.
 */
class MediaThumbnailFetcher(
    private val data: MediaThumbnailData,
    private val options: Options,
    private val resolver: ContentResolver,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val width = options.size.width.pxOrElse { DEFAULT_EDGE_PX }
        val height = options.size.height.pxOrElse { DEFAULT_EDGE_PX }
        val bitmap = load(AndroidSize(width, height))
        return ImageFetchResult(image = edited(bitmap).asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    /** The thumbnail with the photo's edit drawn on it, so an edited photo looks edited everywhere it is listed. The file is never touched. */
    private fun edited(bitmap: Bitmap): Bitmap {
        val recipe = data.recipe?.let(EditRecipeCodec::decode) ?: return bitmap
        return bitmap.withRecipe(recipe)
    }

    /**
     * Blocking platform call, already off the main thread and bounded by the loader's fetcher context
     * (see EikonApplication). Cancelled together with the request when its cell scrolls away.
     */
    private suspend fun load(size: AndroidSize): Bitmap {
        val signal = CancellationSignal()
        val cancelOnCompletion = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) signal.cancel()
        }
        try {
            return resolver.loadThumbnail(data.uri, size, signal)
        } finally {
            cancelOnCompletion?.dispose()
        }
    }

    class Factory(private val resolver: ContentResolver) : Fetcher.Factory<MediaThumbnailData> {
        override fun create(data: MediaThumbnailData, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbnailFetcher(data, options, resolver)
    }

    private companion object {
        const val DEFAULT_EDGE_PX = 512
    }
}

/** The edits of the photos, as stored text by photo id; thumbnails and the viewer draw them. Empty where nothing provides it. */
val LocalEditRecipeTexts = compositionLocalOf<Map<Long, String>> { emptyMap() }

/** A square-friendly thumbnail of [item], sized by the layout it is placed in; with the photo's edit drawn on it unless [applyEdit] is false. */
@Composable
fun MediaThumbnail(
    item: MediaItem,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    applyEdit: Boolean = true,
) = MediaThumbnail(item.id, item.isVideo, item.modifiedAt, modifier, contentScale, applyEdit)

/** Same, from the few fields a collection cover carries. */
@Composable
fun MediaThumbnail(
    id: Long,
    isVideo: Boolean,
    modifiedAt: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    applyEdit: Boolean = true,
) {
    val context = LocalPlatformContext.current
    val recipe = if (isVideo || !applyEdit) null else LocalEditRecipeTexts.current[id]
    val request = remember(id, modifiedAt, recipe) {
        ImageRequest.Builder(context)
            .data(MediaThumbnailData(mediaContentUri(id, isVideo), modifiedAt, recipe))
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}
