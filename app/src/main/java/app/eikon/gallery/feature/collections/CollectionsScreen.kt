package app.eikon.gallery.feature.collections

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.data.db.AlbumSummary
import app.eikon.gallery.data.db.FolderSummary
import app.eikon.gallery.domain.GridSource
import app.eikon.gallery.feature.library.AlbumNameDialog
import app.eikon.gallery.feature.library.ConfirmDialog
import app.eikon.gallery.feature.library.labelRes

/** Everything the user can open from Collections, as the route argument of the grid it leads to. */
fun interface OpenCollection {
    fun open(source: GridSource)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onOpen: OpenCollection,
    onOpenTrash: () -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val trashCount by viewModel.trashCount.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshTrashCount() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.collections_title)) },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(painterResource(R.drawable.ic_add), stringResource(R.string.album_new))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = bottomBar,
    ) { inner ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(TILE_MIN_WIDTH),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            albumSection(albums, viewModel, onOpen)
            personalSection(presets, trashCount, settings?.showHidden == true, onOpen, onOpenTrash)
            folderSection(folders, onOpen)
        }
    }
    if (creating) {
        AlbumNameDialog(
            title = R.string.album_new,
            confirmLabel = R.string.album_create,
            initialName = "",
            onConfirm = {
                creating = false
                viewModel.createAlbum(it)
            },
            onDismiss = { creating = false },
        )
    }
}

private val TILE_MIN_WIDTH = 150.dp

// --- Sections ------------------------------------------------------------------------------------

private fun LazyGridScope.header(@StringRes title: Int) {
    item(key = "header-$title", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun LazyGridScope.albumSection(albums: List<AlbumSummary>, viewModel: CollectionsViewModel, onOpen: OpenCollection) {
    if (albums.isEmpty()) return
    header(R.string.section_albums)
    items(albums, key = { "album-${it.id}" }) { album ->
        AlbumTile(album, viewModel) { onOpen.open(GridSource.Album(album.id)) }
    }
}

private fun LazyGridScope.personalSection(
    presets: List<PresetTile>,
    trashCount: Int?,
    showHidden: Boolean,
    onOpen: OpenCollection,
    onOpenTrash: () -> Unit,
) {
    header(R.string.section_personal)
    items(presets, key = { "preset-${it.kind}" }) { tile ->
        CollectionTile(
            title = stringResource(tile.kind.labelRes()),
            count = tile.count,
            onClick = { onOpen.open(GridSource.Preset(tile.kind)) },
        ) {
            if (tile.cover != null) MediaThumbnail(tile.cover, Modifier.fillMaxSize())
        }
    }
    item(key = "trash") {
        CollectionTile(title = stringResource(R.string.collection_trash), count = trashCount, onClick = onOpenTrash) {
            IconCover(R.drawable.ic_delete)
        }
    }
    if (showHidden) {
        item(key = "hidden") {
            // No count and no cover: the entry must not reveal what is (or is not) hidden.
            CollectionTile(title = stringResource(R.string.collection_hidden), count = null, onClick = { onOpen.open(GridSource.Hidden) }) {
                IconCover(R.drawable.ic_lock)
            }
        }
    }
}

private fun LazyGridScope.folderSection(folders: List<FolderSummary>, onOpen: OpenCollection) {
    if (folders.isEmpty()) return
    header(R.string.section_folders)
    items(folders, key = { "folder-${it.relativePath}" }) { folder ->
        CollectionTile(
            title = folder.name ?: folder.relativePath.trimEnd('/').substringAfterLast('/'),
            count = folder.itemCount,
            onClick = { onOpen.open(GridSource.Folder(folder.relativePath)) },
        ) {
            MediaThumbnail(folder.coverId, folder.coverIsVideo, folder.coverModifiedAt, Modifier.fillMaxSize())
        }
    }
}

// --- Tiles ---------------------------------------------------------------------------------------

/** A cover, title and item count. [cover] fills the square above the text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionTile(
    title: String,
    count: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    cover: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) { cover() }
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
        if (count != null) {
            Text(
                text = pluralStringResource(R.plurals.items_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IconCover(@DrawableRes icon: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
    }
}

/** An album tile; long-press opens rename / delete / reorder. */
@Composable
private fun AlbumTile(album: AlbumSummary, viewModel: CollectionsViewModel, onClick: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    Box {
        CollectionTile(title = album.name, count = album.itemCount, onClick = onClick, onLongClick = { menuOpen = true }) {
            if (album.coverId != null) {
                MediaThumbnail(album.coverId, album.coverIsVideo ?: false, album.coverModifiedAt ?: 0, Modifier.fillMaxSize())
            }
        }
        AlbumMenu(menuOpen, { menuOpen = false }, album, viewModel, onRename = { renaming = true }, onDelete = { deleting = true })
    }
    if (renaming) {
        AlbumNameDialog(R.string.album_rename, R.string.action_save, album.name, {
            renaming = false
            viewModel.renameAlbum(album.id, it)
        }, { renaming = false })
    }
    if (deleting) {
        ConfirmDialog(R.string.album_delete_title, R.string.album_delete_message, R.string.album_delete, {
            deleting = false
            viewModel.deleteAlbum(album.id)
        }, { deleting = false })
    }
}

@Composable
private fun AlbumMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    album: AlbumSummary,
    viewModel: CollectionsViewModel,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuEntry(R.string.album_rename) { onDismiss(); onRename() }
        MenuEntry(R.string.album_move_earlier) { onDismiss(); viewModel.moveAlbumEarlier(album.id) }
        MenuEntry(R.string.album_move_later) { onDismiss(); viewModel.moveAlbumLater(album.id) }
        MenuEntry(R.string.album_delete) { onDismiss(); onDelete() }
    }
}

@Composable
private fun MenuEntry(@StringRes label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

/** Route argument for a collection: the [GridSource] string form, URL-encoded for the path. */
fun collectionRoute(source: GridSource): String = "grid/${Uri.encode(source.toArg())}"

