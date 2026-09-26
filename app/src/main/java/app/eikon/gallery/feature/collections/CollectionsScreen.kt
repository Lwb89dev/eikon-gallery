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
import app.eikon.gallery.data.db.PersonSummary
import app.eikon.gallery.data.duplicates.DuplicateMode
import app.eikon.gallery.domain.GridSource
import app.eikon.gallery.domain.PetKind
import app.eikon.gallery.feature.library.AlbumNameDialog
import app.eikon.gallery.feature.library.ConfirmDialog
import app.eikon.gallery.feature.library.labelRes
import app.eikon.gallery.feature.people.FaceAvatar

/** Everything the user can open from Collections, as the route argument of the grid it leads to. */
fun interface OpenCollection {
    fun open(source: GridSource)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onOpen: OpenCollection,
    onOpenTrash: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenDuplicates: (DuplicateMode) -> Unit,
    onOpenMemories: () -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val trashCount by viewModel.trashCount.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshTrashCount() }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CollectionsTopBar(onCreateAlbum = { creating = true }) },
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
            if (people.isNotEmpty() || settings?.analysis?.people == true) peopleSection(people, onOpenPeople)
            if (settings?.analysis?.semantic == true) petsSection(onOpen)
            if (places != null || settings?.analysis?.places == true) placesSection(places, onOpenPlaces, onOpenTrips)
            memoriesSection(onOpenMemories)
            val duplicatesOn = settings?.analysis?.duplicates == true
            val similarOn = settings?.analysis?.semantic == true
            if (duplicatesOn || similarOn) cleanupSection(duplicatesOn, similarOn, onOpenDuplicates)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionsTopBar(onCreateAlbum: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.collections_title)) },
        actions = {
            IconButton(onClick = onCreateAlbum) { Icon(painterResource(R.drawable.ic_add), stringResource(R.string.album_new)) }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
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
    items(presets, key = { "preset-${it.kind}" }) { tile -> PresetTileView(tile) { onOpen.open(GridSource.Preset(tile.kind)) } }
    item(key = "trash") { IconTile(R.string.collection_trash, trashCount, R.drawable.ic_delete, onOpenTrash) }
    // No count and no cover: the entry must not reveal what is (or is not) hidden.
    if (showHidden) item(key = "hidden") { IconTile(R.string.collection_hidden, null, R.drawable.ic_lock) { onOpen.open(GridSource.Hidden) } }
}

@Composable
private fun PresetTileView(tile: PresetTile, onClick: () -> Unit) {
    CollectionTile(title = stringResource(tile.kind.labelRes()), count = tile.count, onClick = onClick) {
        if (tile.cover != null) MediaThumbnail(tile.cover, Modifier.fillMaxSize())
    }
}

/** A tile with a symbol instead of a picture. */
@Composable
private fun IconTile(@StringRes title: Int, count: Int?, @DrawableRes icon: Int, onClick: () -> Unit) {
    CollectionTile(title = stringResource(title), count = count, onClick = onClick) { IconCover(icon) }
}

private fun LazyGridScope.peopleSection(people: List<PersonSummary>, onOpenPeople: () -> Unit) {
    header(R.string.section_people)
    item(key = "people") {
        CollectionTile(title = stringResource(R.string.collection_people), count = people.size.takeIf { it > 0 }, onClick = onOpenPeople) {
            val first = people.firstOrNull()
            if (first == null) IconCover(R.drawable.ic_person) else FaceAvatar(first, Modifier.fillMaxSize().padding(16.dp))
        }
    }
}

private fun LazyGridScope.placesSection(places: PlacesTile?, onOpenPlaces: () -> Unit, onOpenTrips: () -> Unit) {
    header(R.string.section_places)
    item(key = "places") {
        CollectionTile(title = stringResource(R.string.collection_places), count = null, onClick = onOpenPlaces) {
            if (places == null) IconCover(R.drawable.ic_place) else MediaThumbnail(places.cover.mediaId, false, places.cover.modifiedAt, Modifier.fillMaxSize())
        }
    }
    item(key = "trips") {
        CollectionTile(title = stringResource(R.string.collection_trips), count = null, onClick = onOpenTrips) { IconCover(R.drawable.ic_trip) }
    }
}

private fun LazyGridScope.memoriesSection(onOpenMemories: () -> Unit) {
    header(R.string.section_memories)
    item(key = "memories") {
        CollectionTile(title = stringResource(R.string.collection_memories), count = null, onClick = onOpenMemories) { IconCover(R.drawable.ic_memories) }
    }
}

/** Duplicate photos and similar shots: found by eikon, removed only by the user. */
private fun LazyGridScope.cleanupSection(duplicates: Boolean, similar: Boolean, onOpen: (DuplicateMode) -> Unit) {
    header(R.string.section_cleanup)
    if (duplicates) item(key = "duplicates") { IconTile(R.string.collection_duplicates, null, R.drawable.ic_duplicates) { onOpen(DuplicateMode.DUPLICATES) } }
    if (similar) item(key = "similar") { IconTile(R.string.collection_similar, null, R.drawable.ic_duplicates) { onOpen(DuplicateMode.SIMILAR) } }
}

/** Dogs and cats, found from what the photos show; no counts because they are worked out when a collection is opened. */
private fun LazyGridScope.petsSection(onOpen: OpenCollection) {
    header(R.string.section_pets)
    items(PetKind.entries, key = { "pets-${it.name}" }) { kind ->
        IconTile(if (kind == PetKind.DOG) R.string.collection_dogs else R.string.collection_cats, null, R.drawable.ic_pets) { onOpen.open(GridSource.Pets(kind)) }
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
        CollectionTile(title = album.name, count = album.itemCount, onClick = onClick, onLongClick = { menuOpen = true }) { AlbumCover(album) }
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
private fun AlbumCover(album: AlbumSummary) {
    if (album.coverId != null) MediaThumbnail(album.coverId, album.coverIsVideo ?: false, album.coverModifiedAt ?: 0, Modifier.fillMaxSize())
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

