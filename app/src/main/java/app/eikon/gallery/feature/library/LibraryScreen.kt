package app.eikon.gallery.feature.library

import android.app.Activity
import android.content.IntentSender
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import app.eikon.gallery.R
import app.eikon.gallery.core.ui.peekOrNull
import app.eikon.gallery.core.image.LocalEditRecipeTexts
import app.eikon.gallery.data.db.PersonEntity
import app.eikon.gallery.feature.people.MergePicker
import app.eikon.gallery.data.settings.AppSettings
import app.eikon.gallery.data.sync.SyncStatus
import app.eikon.gallery.domain.GridSource
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.MediaAccess
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.TimelineGrouping
import app.eikon.gallery.domain.TimelineLabelFormatter
import app.eikon.gallery.domain.TimelineLayout
import app.eikon.gallery.feature.info.InfoSheet
import app.eikon.gallery.feature.search.SearchEmpty
import app.eikon.gallery.feature.search.SearchTopBar
import app.eikon.gallery.feature.viewer.MediaViewer
import app.eikon.gallery.feature.viewer.PagingViewerItems
import app.eikon.gallery.feature.viewer.ViewerAction
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * A grid screen: the main Library or any collection (see [GridSource]). Date-grouped grid, selection,
 * and the full-screen viewer on top of it. Waits for the two things it cannot draw without (settings
 * and the first section list), both of which come from local storage within a frame or two.
 *
 * [onBack] is null for the top-level Library; [onOpenSettings] is non-null only there too.
 * [bottomBar] is the navigation bar of top-level screens (absent inside collections).
 */
@Composable
fun LibraryScreen(
    access: MediaAccess,
    onSelectMoreMedia: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    onBack: (() -> Unit)?,
    onEdit: (mediaId: Long) -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: (@Composable () -> Unit)? = null,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val layout by viewModel.timeline.collectAsStateWithLifecycle()
    val loadedSettings = settings
    val loadedLayout = layout
    if (loadedSettings == null || loadedLayout == null) {
        Box(modifier.fillMaxSize())
        return
    }
    val screen = GridScreenConfig(access, onSelectMoreMedia, onOpenAppSettings, onOpenSettings, onBack, onEdit, bottomBar)
    LibraryContent(loadedSettings, loadedLayout, screen, viewModel, modifier)
}

/** What differs between the Library and the collections, bundled to keep signatures short. */
private class GridScreenConfig(
    val access: MediaAccess,
    val onSelectMoreMedia: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenSettings: (() -> Unit)?,
    val onBack: (() -> Unit)?,
    val onEdit: (mediaId: Long) -> Unit,
    val bottomBar: (@Composable () -> Unit)?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(
    settings: AppSettings,
    layout: TimelineLayout,
    screen: GridScreenConfig,
    viewModel: LibraryViewModel,
    modifier: Modifier,
) {
    val items = viewModel.media.collectAsLazyPagingItems()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val albumState by viewModel.album.collectAsStateWithLifecycle()
    val personState by viewModel.person.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // A different filter or sort is a different list: start again at the top.
    val gridState = key(settings.filters, settings.sortField, settings.direction) { rememberLazyGridState() }
    var viewerOpen by rememberSaveable { mutableStateOf(false) }
    var viewerIndex by rememberSaveable { mutableIntStateOf(0) }

    val systemRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onSystemRequestFinished(it.resultCode == Activity.RESULT_OK)
    }
    LibraryEventEffects(viewModel, snackbar) { sender ->
        systemRequest.launch(IntentSenderRequest.Builder(sender).build())
    }
    BackHandler(enabled = selection.isNotEmpty() && !viewerOpen, onBack = viewModel::clearSelection)
    if (albumState == AlbumState.Gone || personState == PersonState.Gone) LaunchedEffect(Unit) { screen.onBack?.invoke() }

    val flights = remember(scope) { ViewerFlights(scope) }
    val openViewer = { index: Int ->
        viewerIndex = index
        viewerOpen = true
        flights.open(items.peekOrNull(index), flights.cellFrame(gridState, layout, index))
    }
    val closeViewer = { animated: Boolean ->
        val index = viewerIndex
        scope.launch {
            revealInGrid(gridState, layout, index)
            if (animated) {
                flights.close(items.peekOrNull(index), flights.cellFrame(gridState, layout, index)) { viewerOpen = false }
            } else {
                viewerOpen = false
            }
        }
        Unit
    }

    Box(
        modifier.fillMaxSize().onGloballyPositioned {
            flights.screenOrigin = it.positionInRoot()
            flights.screenSize = it.size
        },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { GridTopBars(settings, selection, albumState, personState, scrollBehavior, screen, viewModel) },
            bottomBar = { screen.bottomBar?.invoke() },
            snackbarHost = {
                val insetModifier = if (screen.bottomBar == null) Modifier.navigationBarsPadding() else Modifier
                SnackbarHost(snackbar, insetModifier)
            },
        ) { inner ->
            val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Column(Modifier.fillMaxSize().padding(top = inner.calculateTopPadding(), bottom = inner.calculateBottomPadding())) {
                if (screen.access == MediaAccess.LIMITED && viewModel.source == GridSource.Library) {
                    LimitedAccessBanner(screen.onSelectMoreMedia, screen.onOpenAppSettings)
                }
                SyncStatusLine(syncStatus, onRetry = viewModel::retrySync)
                val bottomPadding = if (screen.bottomBar == null) navigationInset else 0.dp
                val gridModifier = Modifier.onGloballyPositioned { flights.gridOrigin = it.positionInRoot() }
                LibraryBody(settings, layout, items, selection, syncStatus, gridState, bottomPadding, viewModel, gridModifier, openViewer)
            }
        }
        LibraryViewer(
            open = viewerOpen,
            index = viewerIndex,
            items = items,
            viewModel = viewModel,
            onEdit = screen.onEdit,
            onPageChanged = { viewerIndex = it },
            onClose = closeViewer,
            hero = flights.hero,
        )
        HeroLayer(flights.hero)
        val preparingShare by viewModel.isPreparingShare.collectAsStateWithLifecycle()
        if (preparingShare) PreparingShare(Modifier.align(Alignment.TopCenter))
    }
}

/** Top bar, or the selection bar with its dialogs and pickers while something is selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridTopBars(
    settings: AppSettings,
    selection: Map<Long, MediaItem>,
    albumState: AlbumState,
    personState: PersonState,
    scrollBehavior: TopAppBarScrollBehavior,
    screen: GridScreenConfig,
    viewModel: LibraryViewModel,
) {
    val source = viewModel.source
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var pickingAlbum by rememberSaveable { mutableStateOf(false) }
    var renamingPerson by rememberSaveable { mutableStateOf(false) }
    var mergingPerson by rememberSaveable { mutableStateOf(false) }
    val album = (albumState as? AlbumState.Present)?.album
    val person = (personState as? PersonState.Present)?.person

    if (selection.isEmpty() && source == GridSource.Search) {
        SearchTopBarHost(settings, viewModel)
    } else if (selection.isEmpty()) {
        GridTopBar(
            source = source,
            albumName = album?.name,
            filters = settings.filters,
            sort = SortChoice(settings.sortField, settings.direction),
            scrollBehavior = scrollBehavior,
            onBack = screen.onBack,
            onFiltersChange = viewModel::setFilters,
            onSortChange = viewModel::setSort,
            onOpenSettings = screen.onOpenSettings,
            albumMenu = if (album != null) AlbumMenuActions({ renaming = true }, { deleting = true }) else null,
            personName = person?.name,
            personMenu = person?.let { personMenu(it, viewModel, { renamingPerson = true }, { mergingPerson = true }) },
            sourceTitle = viewModel.sourceTitle.collectAsStateWithLifecycle().value,
        )
    } else {
        val selected = selection.values.toList()
        val inHidden = source == GridSource.Hidden
        val canPaste by viewModel.canPasteEdits.collectAsStateWithLifecycle()
        val edits = LocalEditRecipeTexts.current
        SelectionTopBar(
            count = selected.size,
            allFavorites = selected.all { it.isFavorite },
            inHidden = inHidden,
            actions = SelectionActions(
                onClear = viewModel::clearSelection,
                onShare = { viewModel.share(selected) },
                onToggleFavorite = { viewModel.toggleFavorite(selected) },
                onDelete = { viewModel.trash(selected) },
                onAddToAlbum = { pickingAlbum = true },
                onToggleHidden = { if (inHidden) viewModel.unhide(selected) else viewModel.hide(selected) },
                onRemoveFromAlbum = if (source is GridSource.Album) ({ viewModel.removeFromAlbum(selected) }) else null,
                onNotThisPerson = if (source is GridSource.Person) ({ viewModel.splitFromPerson(selected) }) else null,
                onNotAFace = if (source is GridSource.Person) ({ viewModel.ignoreFacesOfPerson(selected) }) else null,
                onPasteEdits = if (canPaste && selected.any { !it.isVideo }) ({ viewModel.pasteEdits(selected) }) else null,
                onRevertEdits = if (selected.any { it.id in edits }) ({ viewModel.revertEdits(selected) }) else null,
            ),
        )
        if (pickingAlbum) AlbumPicker(selected, viewModel) { pickingAlbum = false }
    }
    AlbumDialogs(album?.name, renaming, deleting, viewModel, onCloseRename = { renaming = false }, onCloseDelete = { deleting = false })
    PersonDialogs(person?.name, renamingPerson, mergingPerson, viewModel, onCloseRename = { renamingPerson = false }, onCloseMerge = { mergingPerson = false })
}

private fun personMenu(person: PersonEntity, viewModel: LibraryViewModel, onRename: () -> Unit, onMerge: () -> Unit) = PersonMenuActions(
    hasName = person.name != null,
    isFavorite = person.isFavorite,
    isHidden = person.isHidden,
    onRename = onRename,
    onToggleFavorite = { viewModel.setPersonFavorite(!person.isFavorite) },
    onToggleHidden = { viewModel.setPersonHidden(!person.isHidden) },
    onMerge = onMerge,
)

@Composable
private fun PersonDialogs(
    personName: String?,
    renaming: Boolean,
    merging: Boolean,
    viewModel: LibraryViewModel,
    onCloseRename: () -> Unit,
    onCloseMerge: () -> Unit,
) {
    if (renaming) {
        AlbumNameDialog(
            title = R.string.person_rename,
            confirmLabel = R.string.action_save,
            initialName = personName.orEmpty(),
            onConfirm = {
                onCloseRename()
                viewModel.renamePerson(it)
            },
            onDismiss = onCloseRename,
            hint = R.string.person_name_hint,
        )
    }
    if (merging) {
        val choices by viewModel.mergeChoices.collectAsStateWithLifecycle()
        MergePicker(
            choices = choices,
            onPick = {
                onCloseMerge()
                viewModel.mergePersonInto(it.id)
            },
            onDismiss = onCloseMerge,
        )
    }
}

@Composable
private fun SearchTopBarHost(settings: AppSettings, viewModel: LibraryViewModel) {
    val text by viewModel.searchText.collectAsStateWithLifecycle()
    val spec by viewModel.searchSpec.collectAsStateWithLifecycle()
    val analysis by viewModel.analysis.collectAsStateWithLifecycle()
    SearchTopBar(text, viewModel::setSearchText, spec, analysis, settings.analysis)
}

@Composable
private fun AlbumDialogs(
    albumName: String?,
    renaming: Boolean,
    deleting: Boolean,
    viewModel: LibraryViewModel,
    onCloseRename: () -> Unit,
    onCloseDelete: () -> Unit,
) {
    if (renaming && albumName != null) {
        AlbumNameDialog(
            title = R.string.album_rename,
            confirmLabel = R.string.action_save,
            initialName = albumName,
            onConfirm = {
                onCloseRename()
                viewModel.renameAlbum(it)
            },
            onDismiss = onCloseRename,
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = R.string.album_delete_title,
            message = R.string.album_delete_message,
            confirmLabel = R.string.album_delete,
            onConfirm = {
                onCloseDelete()
                viewModel.deleteAlbum()
            },
            onDismiss = onCloseDelete,
        )
    }
}

@Composable
private fun AlbumPicker(selected: List<MediaItem>, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val albums by viewModel.albumChoices.collectAsStateWithLifecycle()
    AddToAlbumSheet(
        albums = albums,
        onPick = {
            onDismiss()
            viewModel.addToAlbum(it.id, it.name, selected)
        },
        onCreate = {
            onDismiss()
            viewModel.createAlbumAndAdd(it, selected)
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun LibraryBody(
    settings: AppSettings,
    layout: TimelineLayout,
    items: LazyPagingItems<MediaItem>,
    selection: Map<Long, MediaItem>,
    syncStatus: SyncStatus,
    gridState: LazyGridState,
    bottomPadding: Dp,
    viewModel: LibraryViewModel,
    modifier: Modifier,
    onOpen: (mediaIndex: Int) -> Unit,
) {
    if (layout.mediaCount == 0) {
        EmptyBody(settings, syncStatus, viewModel)
        return
    }
    val grouping = TimelineGrouping.forColumns(settings.gridColumns)
    val labels = rememberTimelineLabels()
    Box(modifier.fillMaxSize()) {
        LibraryGrid(
            layout = layout,
            items = items,
            columns = settings.gridColumns,
            grouping = grouping,
            labels = labels,
            selection = selection,
            state = gridState,
            contentPadding = PaddingValues(bottom = bottomPadding + 8.dp),
            onOpen = onOpen,
            onToggleSelect = viewModel::toggleSelection,
            onColumnsStep = { delta -> viewModel.setColumns(settings.gridColumns + delta) },
            onBeginDragSelect = viewModel::beginDragSelection,
            onDragSelect = viewModel::dragSelection,
        )
        DateScrubber(
            state = gridState,
            layout = layout,
            monthLabel = labels::monthLabel,
            modifier = Modifier.align(Alignment.TopEnd).padding(bottom = bottomPadding),
        )
    }
}

@Composable
private fun EmptyBody(settings: AppSettings, syncStatus: SyncStatus, viewModel: LibraryViewModel) {
    if (viewModel.source == GridSource.Search) {
        val text by viewModel.searchText.collectAsStateWithLifecycle()
        SearchEmpty(hasQuery = text.isNotBlank())
        return
    }
    val searchingPets by viewModel.searchingPets.collectAsStateWithLifecycle()
    if (syncStatus is SyncStatus.Running || searchingPets) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val filtered = viewModel.source == GridSource.Library && settings.filters.isActive
    if (filtered) {
        EmptyLibrary(R.string.empty_filter, actionLabel = R.string.empty_filter_clear, onAction = { viewModel.setFilters(LibraryFilters.NONE) })
    } else {
        EmptyLibrary(emptyMessage(viewModel.source))
    }
}

@StringRes
private fun emptyMessage(source: GridSource): Int = when (source) {
    GridSource.Library -> R.string.empty_library
    is GridSource.Album -> R.string.empty_album
    is GridSource.Person -> R.string.person_photos_empty
    is GridSource.Pets -> R.string.empty_pets
    is GridSource.Place, is GridSource.Area, is GridSource.Period, is GridSource.Memory -> R.string.empty_collection
    GridSource.Hidden -> R.string.empty_hidden
    else -> R.string.empty_collection
}

@Composable
private fun LibraryViewer(
    open: Boolean,
    index: Int,
    items: LazyPagingItems<MediaItem>,
    viewModel: LibraryViewModel,
    onEdit: (mediaId: Long) -> Unit,
    onPageChanged: (Int) -> Unit,
    onClose: (animated: Boolean) -> Unit,
    hero: HeroController,
) {
    val viewerItems = remember(items) { PagingViewerItems(items) }
    val currentEdit by rememberUpdatedState(onEdit)
    val leading = remember(viewModel) {
        listOf(
            ViewerAction({ R.drawable.ic_share }, { R.string.action_share }) { viewModel.share(listOf(it)) },
            ViewerAction({ R.drawable.ic_edit }, { R.string.action_edit }, visible = { !it.isVideo }) { currentEdit(it.id) },
            ViewerAction(
                icon = { if (it.isFavorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border },
                label = { if (it.isFavorite) R.string.action_unfavorite else R.string.action_favorite },
            ) { viewModel.toggleFavorite(listOf(it)) },
        )
    }
    val trailing = remember(viewModel) {
        listOf(ViewerAction({ R.drawable.ic_delete }, { R.string.action_delete }) { viewModel.trash(listOf(it)) })
    }
    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(VIEWER_IN_MS)),
        exit = fadeOut(tween(VIEWER_OUT_MS)),
    ) {
        MediaViewer(
            items = viewerItems,
            initialPage = index,
            onPageChanged = onPageChanged,
            onClose = onClose,
            shown = hero.viewerShown,
            backdrop = hero::backdrop,
            leadingActions = leading,
            trailingActions = trailing,
            infoSheet = { item, dismiss -> InfoSheet(item, dismiss) },
        )
    }
}

/** A thin moving line across the top while edited photos are drawn to be shared. */
@Composable
private fun PreparingShare(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.share_preparing)
    LinearProgressIndicator(modifier.fillMaxWidth().statusBarsPadding().semantics { contentDescription = label })
}

/** After closing the viewer, scroll the grid so the last photo looked at is on screen. */
private suspend fun revealInGrid(state: LazyGridState, layout: TimelineLayout, mediaIndex: Int) {
    val position = layout.gridPositionOfMedia(mediaIndex)
    if (position < 0) return
    if (state.layoutInfo.visibleItemsInfo.none { it.index == position }) state.scrollToItem(position)
}

/** Turns one-shot view-model events into system dialogs, Sharesheet launches and snackbars. */
@Composable
private fun LibraryEventEffects(
    viewModel: LibraryViewModel,
    snackbar: SnackbarHostState,
    launchSystemRequest: (IntentSender) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val currentLaunch by rememberUpdatedState(launchSystemRequest)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.LaunchSystemRequest -> currentLaunch(event.sender)
                is LibraryEvent.Share -> context.startActivity(event.intent)
                else -> eventMessage(event, resources)?.let { text -> launch { snackbar.showSnackbar(text) } }
            }
        }
    }
}

private fun eventMessage(event: LibraryEvent, resources: Resources): String? = when (event) {
    is LibraryEvent.MovedToTrash -> resources.getQuantityString(R.plurals.moved_to_trash, event.count, event.count)
    is LibraryEvent.AddedToAlbum -> resources.getQuantityString(R.plurals.added_to_album, event.count, event.count, event.albumName)
    is LibraryEvent.Hidden -> resources.getQuantityString(R.plurals.moved_to_hidden, event.count, event.count)
    is LibraryEvent.Unhidden -> resources.getQuantityString(R.plurals.unhidden, event.count, event.count)
    is LibraryEvent.RemovedFromAlbum -> resources.getQuantityString(R.plurals.removed_from_album, event.count, event.count)
    is LibraryEvent.MovedToNewPerson -> resources.getQuantityString(R.plurals.moved_to_new_person, event.count, event.count)
    is LibraryEvent.RemovedFromPeople -> resources.getQuantityString(R.plurals.removed_from_people, event.count, event.count)
    is LibraryEvent.EditsPasted -> resources.getQuantityString(R.plurals.edits_pasted, event.count, event.count)
    is LibraryEvent.EditsReverted -> resources.getQuantityString(R.plurals.edits_reverted, event.count, event.count)
    LibraryEvent.ActionFailed -> resources.getString(R.string.action_failed)
    is LibraryEvent.LaunchSystemRequest, is LibraryEvent.Share -> null
}

@Composable
private fun rememberTimelineLabels(): TimelineLabelFormatter {
    val locale = LocalConfiguration.current.locales[0]
    val today = stringResource(R.string.today)
    val yesterday = stringResource(R.string.yesterday)
    val date = LocalDate.now()
    return remember(locale, today, yesterday, date) {
        TimelineLabelFormatter(date, locale, today, yesterday) { skeleton -> DateFormat.getBestDateTimePattern(locale, skeleton) }
    }
}

private const val VIEWER_IN_MS = 200
private const val VIEWER_OUT_MS = 160
