package app.eikon.gallery.feature.edit

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.domain.ExifFormat
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.VideoTrim
import app.eikon.gallery.feature.library.ConfirmDialog

/**
 * Edit a video without changing it: trim, then the same adjustments, filters and crop a photo has (straighten too; not perspective,
 * sharpening or the vignette, which video does not support yet, see `VideoEditEffects`). "Done" keeps the recipe, "Save a copy" renders a
 * new file, and nothing ever overwrites the original.
 */
@Composable
fun VideoEditScreen(onClose: () -> Unit, modifier: Modifier = Modifier, viewModel: VideoEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmingRevert by rememberSaveable { mutableStateOf(false) }
    val leave = { if (state.changed) confirmingDiscard = true else onClose() }

    BackHandler(onBack = leave)
    LaunchedEffect(viewModel) { viewModel.events.collect { event -> handle(event, snackbar, resources, onClose) } }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        VideoEditBody(state, viewModel, onCancel = leave, onRevert = { confirmingRevert = true })
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
    }
    if (confirmingDiscard) {
        ConfirmDialog(R.string.edit_discard_title, R.string.edit_discard_message_video, R.string.edit_discard, {
            confirmingDiscard = false
            onClose()
        }, { confirmingDiscard = false })
    }
    if (confirmingRevert) {
        ConfirmDialog(R.string.edit_revert_title, R.string.edit_revert_message_video, R.string.edit_revert, {
            confirmingRevert = false
            viewModel.revert()
        }, { confirmingRevert = false })
    }
}

private suspend fun handle(event: VideoEditEvent, snackbar: SnackbarHostState, resources: Resources, onClose: () -> Unit) {
    when (event) {
        VideoEditEvent.Done -> onClose()
        VideoEditEvent.CopySaved -> snackbar.showSnackbar(resources.getString(R.string.edit_copy_saved_video))
        VideoEditEvent.Failed -> snackbar.showSnackbar(resources.getString(R.string.edit_failed_video))
    }
}

@Composable
private fun VideoEditBody(state: VideoEditUiState, viewModel: VideoEditViewModel, onCancel: () -> Unit, onRevert: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        VideoEditTopBar(state, viewModel, onCancel, onRevert)
        state.saving?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val item = state.item
            if (state.loading || item == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                VideoEditPreview(item, state.recipe)
            }
        }
        if (!state.loading) {
            TrimBar(state.recipe.trim, state.durationMs, viewModel::setTrim)
            EditTools(state.recipe, state.tool, state.cropShape, state.filterThumbnails, viewModel, onSelectTool = viewModel::selectTool, isVideo = true)
        }
    }
}

@Composable
private fun VideoEditTopBar(state: VideoEditUiState, viewModel: VideoEditViewModel, onCancel: () -> Unit, onRevert: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_cancel)) }
        Text(stringResource(R.string.edit_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onRevert, enabled = state.recipe != EditRecipe.NONE) { Text(stringResource(R.string.edit_revert)) }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu_more)) }
            VideoEditMenu(menuOpen, { menuOpen = false }, state, viewModel)
        }
        Button(onClick = viewModel::done, enabled = state.item != null, modifier = Modifier.padding(start = 4.dp, end = 8.dp)) { Text(stringResource(R.string.edit_done)) }
    }
}

@Composable
private fun VideoEditMenu(open: Boolean, onDismiss: () -> Unit, state: VideoEditUiState, viewModel: VideoEditViewModel) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.edit_copy_edits)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_copy), contentDescription = null) },
            enabled = !state.recipe.pasteable().isIdentity,
            onClick = { onDismiss(); viewModel.copyEdits() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.edit_paste_edits)) },
            enabled = state.canPaste,
            onClick = { onDismiss(); viewModel.pasteEdits() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.edit_save_copy)) },
            enabled = !state.recipe.isIdentity && state.saving == null,
            onClick = { onDismiss(); viewModel.saveCopy() },
        )
    }
}

/** Where the video starts and ends playing, as a range over its own length; empty (no [durationMs] known yet) shows nothing. */
@Composable
private fun TrimBar(trim: VideoTrim, durationMs: Long, onChange: (startMs: Long, endMs: Long) -> Unit) {
    if (durationMs <= 0L) return
    val endMs = trim.endMs ?: durationMs
    var range by remember(trim, durationMs) { mutableStateOf(fractionOf(trim.startMs, durationMs)..fractionOf(endMs, durationMs)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.edit_trim), style = MaterialTheme.typography.labelMedium)
            Text("${ExifFormat.duration((range.start * durationMs).toLong())} – ${ExifFormat.duration((range.endInclusive * durationMs).toLong())}", style = MaterialTheme.typography.labelMedium)
        }
        RangeSlider(
            value = range,
            onValueChange = { range = it },
            onValueChangeFinished = { onChange((range.start * durationMs).toLong(), (range.endInclusive * durationMs).toLong()) },
            colors = SliderDefaults.colors(),
        )
    }
}

private fun fractionOf(positionMs: Long, durationMs: Long): Float = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
