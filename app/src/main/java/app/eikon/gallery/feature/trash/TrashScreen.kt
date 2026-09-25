package app.eikon.gallery.feature.trash

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.data.mediastore.TrashedMedia
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.feature.info.InfoSheet
import app.eikon.gallery.feature.library.EmptyLibrary
import app.eikon.gallery.feature.library.MediaCell
import app.eikon.gallery.feature.viewer.ListViewerItems
import app.eikon.gallery.feature.viewer.MediaViewer
import app.eikon.gallery.feature.viewer.ViewerAction

/** Recently deleted: the system trash with days remaining, restore, and delete-for-good. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    var viewerIndex by rememberSaveable { mutableIntStateOf(0) }

    val systemRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onSystemRequestFinished(it.resultCode == Activity.RESULT_OK)
    }
    TrashEventEffects(viewModel, snackbar) { systemRequest.launch(IntentSenderRequest.Builder(it).build()) }
    BackHandler(enabled = selection.isNotEmpty() && !viewerOpen, onBack = viewModel::clearSelection)

    val items = (state as? TrashUiState.Loaded)?.items.orEmpty()
    Box(modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { TrashTopBar(items, selection, onBack, viewModel) },
            snackbarHost = { SnackbarHost(snackbar, Modifier.navigationBarsPadding()) },
        ) { inner ->
            Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding())) {
                TrashBody(state, items, selection, viewModel) { index ->
                    viewerIndex = index
                    viewerOpen = true
                }
            }
        }
        TrashViewer(viewerOpen, viewerIndex, items, viewModel, { viewerIndex = it }) { viewerOpen = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashTopBar(items: List<TrashedMedia>, selection: Set<Long>, onBack: () -> Unit, viewModel: TrashViewModel) {
    if (selection.isEmpty()) {
        TopAppBar(
            title = { Text(stringResource(R.string.collection_trash)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        return
    }
    val chosen = items.filter { it.item.id in selection }.map { it.item }
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, selection.size, selection.size)) },
        navigationIcon = {
            IconButton(onClick = viewModel::clearSelection) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_clear_selection))
            }
        },
        actions = {
            TextButton(onClick = { viewModel.restore(chosen) }) { Text(stringResource(R.string.trash_restore)) }
            TextButton(onClick = { viewModel.deleteForever(chosen) }) { Text(stringResource(R.string.trash_delete_forever)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun TrashBody(
    state: TrashUiState,
    items: List<TrashedMedia>,
    selection: Set<Long>,
    viewModel: TrashViewModel,
    onOpen: (Int) -> Unit,
) {
    when {
        state is TrashUiState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        state is TrashUiState.Failed -> EmptyLibrary(R.string.info_error)
        items.isEmpty() -> EmptyLibrary(R.string.trash_empty)
        else -> TrashGrid(items, selection, viewModel, onOpen)
    }
}

@Composable
private fun TrashGrid(items: List<TrashedMedia>, selection: Set<Long>, viewModel: TrashViewModel, onOpen: (Int) -> Unit) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val selectLabel = stringResource(R.string.action_select)
    Text(
        text = stringResource(R.string.trash_info),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        contentPadding = PaddingValues(bottom = bottom + 8.dp),
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalArrangement = Arrangement.spacedBy(1.5.dp),
    ) {
        items(items, key = { it.item.id }) { media ->
            val index = items.indexOf(media)
            val selectionMode = selection.isNotEmpty()
            MediaCell(
                item = media.item,
                selected = media.item.id in selection,
                selectionMode = selectionMode,
                selectLabel = selectLabel,
                onClick = { if (selectionMode) viewModel.toggleSelection(media.item.id) else onOpen(index) },
                onLongClick = { if (!selectionMode) viewModel.toggleSelection(media.item.id) },
                overlay = { DaysLeftBadge(viewModel.daysLeft(media)) },
            )
        }
    }
}

@Composable
private fun BoxScope.DaysLeftBadge(days: Int) {
    Text(
        text = pluralStringResource(R.plurals.days_left_short, days, days),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(4.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun TrashViewer(
    open: Boolean,
    index: Int,
    items: List<TrashedMedia>,
    viewModel: TrashViewModel,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val viewerItems = remember(items) { ListViewerItems(items.map { it.item }) }
    val leading = remember(viewModel) {
        listOf(ViewerAction({ R.drawable.ic_restore }, { R.string.trash_restore }) { viewModel.restore(listOf(it)) })
    }
    val trailing = remember(viewModel) {
        listOf(ViewerAction({ R.drawable.ic_delete }, { R.string.trash_delete_forever }) { viewModel.deleteForever(listOf(it)) })
    }
    AnimatedVisibility(visible = open, enter = fadeIn(), exit = fadeOut()) {
        MediaViewer(
            items = viewerItems,
            initialPage = index,
            onPageChanged = onPageChanged,
            onClose = { onClose() },
            leadingActions = leading,
            trailingActions = trailing,
            infoSheet = { item: MediaItem, dismiss -> InfoSheet(item, dismiss) },
        )
    }
}

@Composable
private fun TrashEventEffects(
    viewModel: TrashViewModel,
    snackbar: SnackbarHostState,
    launchSystemRequest: (android.content.IntentSender) -> Unit,
) {
    val resources = LocalResources.current
    val currentLaunch by rememberUpdatedState(launchSystemRequest)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val text = when (event) {
                is TrashEvent.LaunchSystemRequest -> {
                    currentLaunch(event.sender)
                    null
                }
                is TrashEvent.Restored -> resources.getQuantityString(R.plurals.trash_restored, event.count, event.count)
                is TrashEvent.Deleted -> resources.getQuantityString(R.plurals.trash_deleted, event.count, event.count)
                TrashEvent.ActionFailed -> resources.getString(R.string.action_failed)
            }
            if (text != null) launch { snackbar.showSnackbar(text) }
        }
    }
}

private const val GRID_COLUMNS = 4
