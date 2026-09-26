package app.eikon.gallery.feature.library

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.paging.compose.LazyPagingItems
import app.eikon.gallery.core.ui.peekOrNull
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.TimelineLayout
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

private const val SETTLE_MS = 300L

/**
 * Tells the background analysis which photos are on screen once scrolling settles, so those are looked at before the rest of the library. Nothing is stored: the ids are held in memory
 * while the screen is open and forgotten when it goes away.
 */
@OptIn(FlowPreview::class)
@Composable
fun ReportVisiblePhotos(state: LazyGridState, layout: TimelineLayout, items: LazyPagingItems<MediaItem>, viewModel: LibraryViewModel) {
    val currentLayout by rememberUpdatedState(layout)
    LaunchedEffect(state, items) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.map { it.index } }
            .debounce(SETTLE_MS)
            .collect { positions -> viewModel.onVisible(idsAt(positions, currentLayout, items)) }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.onVisible(emptyList()) } }
}

private fun idsAt(positions: List<Int>, layout: TimelineLayout, items: LazyPagingItems<MediaItem>): List<Long> =
    positions.mapNotNull { position -> layout.mediaIndexOfGridPosition(position)?.let { items.peekOrNull(it)?.id } }
