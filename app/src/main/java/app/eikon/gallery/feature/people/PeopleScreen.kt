package app.eikon.gallery.feature.people

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.data.db.PersonSummary
import app.eikon.gallery.feature.library.AlbumNameDialog

/** Everyone eikon has grouped from the faces in the photos, with their names and photo counts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    onBack: () -> Unit,
    onOpenPerson: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PeopleViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.people_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        if (state.people.isEmpty() && state.hiddenCount == 0) {
            EmptyPeople(state, onOpenSettings, Modifier.padding(inner))
        } else {
            PeopleGrid(state, viewModel, onOpenPerson, inner)
        }
    }
}

@Composable
private fun PeopleGrid(state: PeopleState, viewModel: PeopleViewModel, onOpenPerson: (Long) -> Unit, inner: PaddingValues) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(AVATAR_CELL),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding() + 8.dp, bottom = inner.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.people, key = { it.id }) { person -> PersonCell(person, viewModel) { onOpenPerson(person.id) } }
        if (state.hiddenCount > 0) item(span = { GridItemSpan(maxLineSpan) }) { HiddenToggle(state, viewModel) }
    }
}

/** Shows or hides the people the user has hidden. */
@Composable
private fun HiddenToggle(state: PeopleState, viewModel: PeopleViewModel) {
    val label = if (state.showHidden) stringResource(R.string.people_hide_hidden) else stringResource(R.string.people_show_hidden, state.hiddenCount)
    TextButton(onClick = viewModel::toggleShowHidden) { Text(label) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PersonCell(person: PersonSummary, viewModel: PeopleViewModel, onClick: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FaceAvatar(person, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(
                text = person.name ?: stringResource(R.string.person_unnamed),
                style = MaterialTheme.typography.bodyMedium,
                color = if (person.name == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = pluralStringResource(R.plurals.items_count, person.photoCount, person.photoCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (person.isFavorite) {
            Icon(
                painterResource(R.drawable.ic_favorite),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
            )
        }
        PersonMenu(person, menuOpen, { menuOpen = false }, { renaming = true }, viewModel)
    }
    if (renaming) {
        AlbumNameDialog(R.string.person_rename, R.string.action_save, person.name.orEmpty(), {
            renaming = false
            viewModel.rename(person.id, it)
        }, { renaming = false }, hint = R.string.person_name_hint, note = R.string.person_same_name_note)
    }
}

@Composable
private fun PersonMenu(person: PersonSummary, expanded: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, viewModel: PeopleViewModel) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        val rename = if (person.name == null) R.string.person_add_name else R.string.person_rename
        DropdownMenuItem(text = { Text(stringResource(rename)) }, onClick = { onDismiss(); onRename() })
        val favorite = if (person.isFavorite) R.string.person_unfavorite else R.string.person_favorite
        DropdownMenuItem(text = { Text(stringResource(favorite)) }, onClick = { onDismiss(); viewModel.setFavorite(person.id, !person.isFavorite) })
        val hide = if (person.isHidden) R.string.person_unhide else R.string.person_hide
        DropdownMenuItem(text = { Text(stringResource(hide)) }, onClick = { onDismiss(); viewModel.setHidden(person.id, !person.isHidden) })
    }
}

/** Nothing to list: says why (off, still analysing, or truly no faces) instead of showing a blank screen. */
@Composable
private fun EmptyPeople(state: PeopleState, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val progress = state.progress
        val message = when {
            !state.enabled -> stringResource(R.string.people_empty_off)
            progress != null && !progress.isComplete -> stringResource(R.string.people_empty_working, percent(progress.done, progress.total))
            else -> stringResource(R.string.people_empty_done)
        }
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (!state.enabled) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.people_go_settings)) }
    }
}

private fun percent(done: Int, total: Int): Int = if (total == 0) 0 else done * PERCENT / total

private val AVATAR_CELL = 104.dp
private const val PERCENT = 100
