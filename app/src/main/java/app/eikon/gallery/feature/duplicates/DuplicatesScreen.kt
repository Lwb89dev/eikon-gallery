package app.eikon.gallery.feature.duplicates

import android.app.Activity
import android.content.res.Resources
import android.content.IntentSender
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.data.duplicates.DuplicateKind
import app.eikon.gallery.data.duplicates.DuplicateMode
import app.eikon.gallery.domain.MediaItem

/**
 * Copies of the same photo, or shots of one moment that look alike. eikon only *finds* them: every photo that leaves goes to
 * Recently deleted through Android's own confirmation, one group at a time, and can be restored from there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DuplicatesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val systemRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onSystemRequestFinished(it.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> handle(event, snackbar, resources) { systemRequest.launch(IntentSenderRequest.Builder(it).build()) } }
    }
    val title = if (viewModel.mode == DuplicateMode.DUPLICATES) R.string.duplicates_title else R.string.similar_title
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { DuplicatesTopBar(title, onBack) },
    ) { inner ->
        when {
            state.loading -> Box(Modifier.padding(inner).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.groups.isEmpty() -> EmptyGroups(viewModel.mode, state, onOpenSettings, Modifier.padding(inner))
            else -> GroupList(viewModel, state, inner)
        }
    }
}

/** One event of the view model: the system dialog to open, or what to say. */
private suspend fun handle(event: DuplicatesEvent, snackbar: SnackbarHostState, resources: Resources, launch: (IntentSender) -> Unit) {
    when (event) {
        is DuplicatesEvent.LaunchSystemRequest -> launch(event.sender)
        is DuplicatesEvent.MovedToTrash -> snackbar.showSnackbar(resources.getQuantityString(R.plurals.moved_to_trash, event.count, event.count))
        DuplicatesEvent.ActionFailed -> snackbar.showSnackbar(resources.getString(R.string.action_failed))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DuplicatesTopBar(@StringRes title: Int, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(title)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun GroupList(viewModel: DuplicatesViewModel, state: DuplicatesState, inner: PaddingValues) {
    LazyColumn(
        contentPadding = PaddingValues(top = inner.calculateTopPadding() + 8.dp, bottom = inner.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "note") {
            Text(stringResource(R.string.duplicates_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.groups, key = { it.entry.key }) { group -> GroupCard(group, viewModel) }
    }
}

@Composable
private fun GroupCard(group: GroupState, viewModel: DuplicatesViewModel) {
    val entry = group.entry
    val similar = viewModel.mode == DuplicateMode.SIMILAR
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(groupTitle(entry.kind, entry.items.size), style = MaterialTheme.typography.titleSmall)
        ItemRow(group, viewModel)
        val count = group.marked.size
        Button(onClick = { viewModel.trashMarked(entry.key) }, enabled = count > 0 && count < entry.items.size, modifier = Modifier.fillMaxWidth()) {
            val label = if (similar) R.plurals.similar_trash_marked else R.plurals.duplicate_keep_best
            Text(pluralStringResource(label, count, count))
        }
        TextButton(onClick = { viewModel.dismiss(entry.key) }) {
            Text(stringResource(if (similar) R.string.similar_dismiss else R.string.duplicate_dismiss))
        }
    }
}

/** The photos of a group side by side, each one a tap away from being marked to go. */
@Composable
private fun ItemRow(group: GroupState, viewModel: DuplicatesViewModel) {
    val entry = group.entry
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(entry.items, key = { it.id }) { item ->
            ItemCell(item, marked = item.id in group.marked, best = item.id == entry.bestId) { viewModel.toggle(entry.key, item.id) }
        }
    }
}

@Composable
private fun groupTitle(kind: DuplicateKind?, count: Int): String = when (kind) {
    DuplicateKind.EXACT -> pluralStringResource(R.plurals.duplicate_exact, count, count)
    DuplicateKind.VISUAL -> pluralStringResource(R.plurals.duplicate_visual, count, count)
    null -> pluralStringResource(R.plurals.similar_group, count, count)
}

@Composable
private fun ItemCell(item: MediaItem, marked: Boolean, best: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.width(120.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val outline = if (marked) MaterialTheme.colorScheme.error else if (best) MaterialTheme.colorScheme.primary else Color.Transparent
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, outline, RoundedCornerShape(8.dp))) {
            MediaThumbnail(item, Modifier.fillMaxSize())
            if (marked) MarkedOverlay()
        }
        val badge = when {
            marked -> stringResource(R.string.duplicate_marked)
            best -> stringResource(R.string.duplicate_best)
            else -> stringResource(R.string.duplicate_kept)
        }
        Text(badge, style = MaterialTheme.typography.labelMedium, color = if (marked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text("${item.width} × ${item.height}", style = MaterialTheme.typography.bodySmall)
        Text(Formatter.formatShortFileSize(context, item.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            DateUtils.formatDateTime(context, item.takenAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Dims a photo that is marked to go and says so. */
@Composable
private fun MarkedOverlay() {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), Alignment.Center) {
        Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.duplicate_marked), tint = Color.White)
    }
}

@Composable
private fun EmptyGroups(mode: DuplicateMode, state: DuplicatesState, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val progress = state.progress
        val message = when {
            !state.enabled -> stringResource(if (mode == DuplicateMode.DUPLICATES) R.string.duplicates_empty_off else R.string.similar_empty_off)
            progress != null && !progress.isComplete -> stringResource(R.string.duplicates_empty_working, if (progress.total == 0) 0 else progress.done * 100 / progress.total)
            else -> stringResource(if (mode == DuplicateMode.DUPLICATES) R.string.duplicates_empty_done else R.string.similar_empty_done)
        }
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (!state.enabled) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.people_go_settings)) }
    }
}
