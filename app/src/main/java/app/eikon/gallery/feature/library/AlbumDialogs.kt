package app.eikon.gallery.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.data.AlbumNames
import app.eikon.gallery.data.db.AlbumSummary

/** Text-field dialog for naming or renaming an album. Confirm is disabled while the name is blank. */
@Composable
fun AlbumNameDialog(
    @StringRes title: Int,
    @StringRes confirmLabel: Int,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    @StringRes hint: Int = R.string.album_name_hint,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(AlbumNames.MAX_LENGTH) },
                singleLine = true,
                label = { Text(stringResource(hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = AlbumNames.clean(name) != null) {
                Text(stringResource(confirmLabel))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ConfirmDialog(
    @StringRes title: Int,
    @StringRes message: Int,
    @StringRes confirmLabel: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirmLabel)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Album picker for "Add to album": existing albums with cover and count, plus "New album". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToAlbumSheet(
    albums: List<AlbumSummary>,
    onPick: (AlbumSummary) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.navigationBarsPadding()) {
            item { NewAlbumRow { creating = true } }
            items(albums, key = { it.id }) { album -> AlbumRow(album) { onPick(album) } }
        }
    }
    if (creating) {
        AlbumNameDialog(
            title = R.string.album_new,
            confirmLabel = R.string.album_create,
            initialName = "",
            onConfirm = {
                creating = false
                onCreate(it)
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun NewAlbumRow(onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(stringResource(R.string.album_new), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun AlbumRow(album: AlbumSummary, onClick: () -> Unit) {
    Row(
        Modifier.clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverThumbnail(album.coverId, album.coverIsVideo ?: false, album.coverModifiedAt ?: 0, Modifier.size(48.dp))
        Column {
            Text(album.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                pluralStringResource(R.plurals.items_count, album.itemCount, album.itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A rounded square showing the newest item of a collection, or a neutral tile while it is empty. */
@Composable
fun CoverThumbnail(coverId: Long?, isVideo: Boolean, modifiedAt: Long, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (coverId != null) MediaThumbnail(coverId, isVideo, modifiedAt, Modifier.fillMaxSize())
    }
}
