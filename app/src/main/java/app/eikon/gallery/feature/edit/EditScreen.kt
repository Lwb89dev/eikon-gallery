package app.eikon.gallery.feature.edit

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
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.feature.library.ConfirmDialog

/**
 * Edit a photo without changing it. The sliders change a recipe that is drawn over the photo; "Done" keeps the recipe, "Save a copy" makes a new
 * file, and nothing ever overwrites the original. Hold the picture to see the original.
 */
@Composable
fun EditScreen(onClose: () -> Unit, modifier: Modifier = Modifier, viewModel: EditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmingRevert by rememberSaveable { mutableStateOf(false) }
    val leave = { if (state.changed) confirmingDiscard = true else onClose() }

    BackHandler(onBack = leave)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                EditEvent.Done -> onClose()
                is EditEvent.CopySaved -> snackbar.showSnackbar(resources.getString(if (event.keptMetadata) R.string.edit_copy_saved else R.string.edit_copy_saved_no_details))
                EditEvent.Failed -> snackbar.showSnackbar(resources.getString(R.string.edit_failed))
            }
        }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            EditTopBar(state, viewModel, onCancel = leave, onRevert = { confirmingRevert = true })
            state.saving?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center)) else EditPreview(state, viewModel)
            }
            if (!state.loading) EditTools(state, viewModel)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
    }
    if (confirmingDiscard) {
        ConfirmDialog(R.string.edit_discard_title, R.string.edit_discard_message, R.string.edit_discard, {
            confirmingDiscard = false
            onClose()
        }, { confirmingDiscard = false })
    }
    if (confirmingRevert) {
        ConfirmDialog(R.string.edit_revert_title, R.string.edit_revert_message, R.string.edit_revert, {
            confirmingRevert = false
            viewModel.revert()
        }, { confirmingRevert = false })
    }
}

@Composable
private fun EditTopBar(state: EditUiState, viewModel: EditViewModel, onCancel: () -> Unit, onRevert: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_cancel)) }
        Text(stringResource(R.string.edit_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onRevert, enabled = state.recipe != EditRecipe.NONE) { Text(stringResource(R.string.edit_revert)) }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu_more)) }
            EditMenu(menuOpen, { menuOpen = false }, state, viewModel)
        }
        Button(onClick = viewModel::done, enabled = state.item != null, modifier = Modifier.padding(start = 4.dp, end = 8.dp)) { Text(stringResource(R.string.edit_done)) }
    }
}

@Composable
private fun EditMenu(open: Boolean, onDismiss: () -> Unit, state: EditUiState, viewModel: EditViewModel) {
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
