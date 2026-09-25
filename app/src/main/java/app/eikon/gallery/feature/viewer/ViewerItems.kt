package app.eikon.gallery.feature.viewer

import androidx.compose.runtime.Immutable
import androidx.paging.compose.LazyPagingItems
import app.eikon.gallery.core.ui.getOrNull
import app.eikon.gallery.core.ui.peekOrNull
import app.eikon.gallery.domain.MediaItem

/**
 * What the viewer pages through. The library backs it with the paged index (so swiping walks a
 * whole huge library), the trash with a plain list. Out-of-range indexes yield null.
 */
interface ViewerItems {
    val count: Int

    /** The item if already loaded; never triggers a load. */
    fun peek(index: Int): MediaItem?

    /** The item, triggering its load if needed; null while it is still a placeholder. */
    fun get(index: Int): MediaItem?
}

class PagingViewerItems(private val items: LazyPagingItems<MediaItem>) : ViewerItems {
    override val count: Int get() = items.itemCount
    override fun peek(index: Int): MediaItem? = items.peekOrNull(index)
    override fun get(index: Int): MediaItem? = items.getOrNull(index)
}

class ListViewerItems(private val list: List<MediaItem>) : ViewerItems {
    override val count: Int get() = list.size
    override fun peek(index: Int): MediaItem? = list.getOrNull(index)
    override fun get(index: Int): MediaItem? = list.getOrNull(index)
}

/** One button of the viewer's bottom bar; icon and label may depend on the item (e.g. favorite state). */
@Immutable
class ViewerAction(
    /** Drawable resource id for the item. */
    val icon: (MediaItem) -> Int,
    /** String resource id for the item. */
    val label: (MediaItem) -> Int,
    val onClick: (MediaItem) -> Unit,
)
