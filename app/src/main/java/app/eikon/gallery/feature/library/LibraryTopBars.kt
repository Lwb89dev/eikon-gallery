package app.eikon.gallery.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.domain.CategoryFilter
import app.eikon.gallery.domain.GridSource
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.PetKind
import app.eikon.gallery.domain.PresetKind
import app.eikon.gallery.domain.SortDirection
import app.eikon.gallery.domain.SortField
import app.eikon.gallery.domain.TypeFilter

@StringRes
fun TypeFilter.labelRes(): Int = when (this) {
    TypeFilter.ALL -> R.string.filter_all
    TypeFilter.PHOTOS -> R.string.filter_photos
    TypeFilter.VIDEOS -> R.string.filter_videos
}

@StringRes
fun CategoryFilter.labelRes(): Int = when (this) {
    CategoryFilter.SCREENSHOTS -> R.string.filter_screenshots
    CategoryFilter.SCREEN_RECORDINGS -> R.string.filter_screen_recordings
    CategoryFilter.PANORAMAS -> R.string.filter_panoramas
    CategoryFilter.RAW -> R.string.filter_raw
}

@StringRes
fun PresetKind.labelRes(): Int = when (this) {
    PresetKind.FAVORITES -> R.string.collection_favorites
    PresetKind.RECENT -> R.string.collection_recent
    PresetKind.VIDEOS -> R.string.collection_videos
    PresetKind.SCREENSHOTS -> R.string.collection_screenshots
    PresetKind.SCREEN_RECORDINGS -> R.string.collection_screen_recordings
    PresetKind.PANORAMAS -> R.string.collection_panoramas
    PresetKind.RAW -> R.string.collection_raw
}

/** The title of a screen whose name has to be looked up (a place from the place data). */
sealed interface SourceTitle {
    data class Text(val text: String) : SourceTitle
    data class NearCity(val name: String) : SourceTitle
    data object OtherPlaces : SourceTitle
    data class OfMemory(val id: app.eikon.gallery.domain.memories.MemoryId, val personName: String?) : SourceTitle
}

/** What the sort menu currently shows as selected. */
data class SortChoice(val field: SortField, val direction: SortDirection)

/** Album-only actions in the overflow menu of an album screen. */
class AlbumMenuActions(val onRename: () -> Unit, val onDelete: () -> Unit)

/** Person-only actions in the overflow menu of a person screen. */
class PersonMenuActions(
    val hasName: Boolean,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val onRename: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onToggleHidden: () -> Unit,
    val onMerge: () -> Unit,
)

/**
 * Title bar of a grid screen. In the main Library, active filters are summarised in the title and the
 * navigation arrow clears them, so a filtered view reads like a collection you can step out of.
 * Every other source has a fixed title and a plain back arrow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridTopBar(
    source: GridSource,
    albumName: String?,
    filters: LibraryFilters,
    sort: SortChoice,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: (() -> Unit)?,
    onFiltersChange: (LibraryFilters) -> Unit,
    onSortChange: (SortField, SortDirection) -> Unit,
    onOpenSettings: (() -> Unit)?,
    albumMenu: AlbumMenuActions?,
    personName: String? = null,
    personMenu: PersonMenuActions? = null,
    sourceTitle: SourceTitle? = null,
) {
    val isLibrary = source == GridSource.Library
    val filtered = isLibrary && filters.isActive
    TopAppBar(
        title = { Text(gridTitle(source, albumName, personName, sourceTitle, filters)) },
        navigationIcon = {
            val onNavigate = if (filtered) ({ onFiltersChange(LibraryFilters.NONE) }) else onBack
            if (onNavigate != null) {
                IconButton(onClick = onNavigate) {
                    val label = if (filtered) R.string.filter_clear else R.string.action_back
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(label))
                }
            }
        },
        actions = {
            if (isLibrary) FilterMenu(filters, onFiltersChange)
            SortMenu(sort, onSortChange)
            if (onOpenSettings != null) OverflowMenu { close -> SettingsItem { close(); onOpenSettings() } }
            if (albumMenu != null) AlbumOverflow(albumMenu)
            if (personMenu != null) PersonOverflow(personMenu)
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun gridTitle(source: GridSource, albumName: String?, personName: String?, sourceTitle: SourceTitle?, filters: LibraryFilters): String = when (source) {
    GridSource.Library -> if (filters.isActive) filterSummary(filters) else stringResource(R.string.library_title)
    is GridSource.Preset -> stringResource(source.kind.labelRes())
    is GridSource.Album -> albumName.orEmpty()
    is GridSource.Person -> personName ?: stringResource(R.string.person_unnamed)
    is GridSource.Pets -> stringResource(if (source.kind == PetKind.DOG) R.string.collection_dogs else R.string.collection_cats)
    is GridSource.Place, is GridSource.Area -> when (sourceTitle) {
        is SourceTitle.Text -> sourceTitle.text
        is SourceTitle.NearCity -> stringResource(R.string.place_near, sourceTitle.name)
        SourceTitle.OtherPlaces, null, is SourceTitle.OfMemory -> stringResource(R.string.place_unknown)
    }
    is GridSource.Period -> source.label ?: periodTitle(source)
    is GridSource.Memory -> (sourceTitle as? SourceTitle.OfMemory)?.let { app.eikon.gallery.feature.memories.memoryTitle(it.id, it.personName) }.orEmpty()
    is GridSource.Folder -> source.relativePath.trimEnd('/').substringAfterLast('/')
    GridSource.Hidden -> stringResource(R.string.collection_hidden)
    GridSource.Search -> stringResource(R.string.nav_search)
}

@Composable
private fun periodTitle(period: GridSource.Period): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return android.text.format.DateUtils.formatDateRange(
        context, period.startMillis, period.endMillis - 1,
        android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_YEAR,
    )
}

@Composable
private fun filterSummary(filters: LibraryFilters): String = buildList {
    if (filters.type != TypeFilter.ALL) add(stringResource(filters.type.labelRes()))
    if (filters.favoritesOnly) add(stringResource(R.string.filter_favorites))
    if (filters.editedOnly) add(stringResource(R.string.filter_edited))
    filters.category?.let { add(stringResource(it.labelRes())) }
}.joinToString(" · ")

/** Selection bar: the common actions as icons, everything else in the overflow menu. */
class SelectionActions(
    val onClear: () -> Unit,
    val onShare: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onDelete: () -> Unit,
    val onAddToAlbum: () -> Unit,
    /** Hide from the library, or (inside Hidden) bring back. */
    val onToggleHidden: () -> Unit,
    /** Only inside an album. */
    val onRemoveFromAlbum: (() -> Unit)?,
    /** Only inside a person: the selected photos are not this person. */
    val onNotThisPerson: (() -> Unit)? = null,
    /** Only inside a person: the faces in the selected photos are not faces. */
    val onNotAFace: (() -> Unit)? = null,
    /** Only when edits were copied and a photo is selected: give the selected photos that look. */
    val onPasteEdits: (() -> Unit)? = null,
    /** Only when a selected photo has an edit. */
    val onRevertEdits: (() -> Unit)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    allFavorites: Boolean,
    inHidden: Boolean,
    actions: SelectionActions,
) {
    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.selected_count, count, count)) },
        navigationIcon = {
            IconButton(onClick = actions.onClear) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_clear_selection))
            }
        },
        actions = {
            IconButton(onClick = actions.onShare) {
                Icon(painterResource(R.drawable.ic_share), stringResource(R.string.action_share))
            }
            IconButton(onClick = actions.onToggleFavorite) {
                val icon = if (allFavorites) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                val label = if (allFavorites) R.string.action_unfavorite else R.string.action_favorite
                Icon(painterResource(icon), stringResource(label))
            }
            IconButton(onClick = actions.onDelete) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.action_delete))
            }
            SelectionOverflow(inHidden, actions)
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

@Composable
private fun SelectionOverflow(inHidden: Boolean, actions: SelectionActions) {
    OverflowMenu { close ->
        MenuItem(R.string.action_add_to_album) { close(); actions.onAddToAlbum() }
        actions.onRemoveFromAlbum?.let { remove -> MenuItem(R.string.action_remove_from_album) { close(); remove() } }
        actions.onNotThisPerson?.let { split -> MenuItem(R.string.person_not_this) { close(); split() } }
        actions.onNotAFace?.let { ignore -> MenuItem(R.string.person_remove_from_people) { close(); ignore() } }
        actions.onPasteEdits?.let { paste -> MenuItem(R.string.action_paste_edits) { close(); paste() } }
        actions.onRevertEdits?.let { revert -> MenuItem(R.string.action_revert_edits) { close(); revert() } }
        val hideLabel = if (inHidden) R.string.action_unhide else R.string.action_hide
        MenuItem(hideLabel) { close(); actions.onToggleHidden() }
    }
}

// --- Menus ---------------------------------------------------------------------------------------

/** Filters combine with AND; the menu stays open so several can be set in one go. */
@Composable
private fun FilterMenu(filters: LibraryFilters, onChange: (LibraryFilters) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            val tint = if (filters.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Icon(painterResource(R.drawable.ic_filter_list), stringResource(R.string.menu_filter), tint = tint)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuHeader(R.string.filter_type_title)
            TypeFilter.entries.forEach { type ->
                CheckableItem(stringResource(type.labelRes()), filters.type == type) { onChange(filters.copy(type = type)) }
            }
            HorizontalDivider()
            CheckableItem(stringResource(R.string.filter_favorites_only), filters.favoritesOnly) {
                onChange(filters.copy(favoritesOnly = !filters.favoritesOnly))
            }
            CheckableItem(stringResource(R.string.filter_edited_only), filters.editedOnly) {
                onChange(filters.copy(editedOnly = !filters.editedOnly))
            }
            HorizontalDivider()
            MenuHeader(R.string.filter_kind_title)
            CheckableItem(stringResource(R.string.filter_any), filters.category == null) { onChange(filters.copy(category = null)) }
            CategoryFilter.entries.forEach { category ->
                CheckableItem(stringResource(category.labelRes()), filters.category == category) {
                    onChange(filters.copy(category = category))
                }
            }
            if (filters.isActive) {
                HorizontalDivider()
                MenuItem(R.string.filter_clear_all) {
                    expanded = false
                    onChange(LibraryFilters.NONE)
                }
            }
        }
    }
}

@Composable
private fun SortMenu(sort: SortChoice, onSortChange: (SortField, SortDirection) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_sort), stringResource(R.string.menu_sort))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val choose = { field: SortField, direction: SortDirection ->
                expanded = false
                onSortChange(field, direction)
            }
            CheckableItem(stringResource(R.string.sort_by_date_taken), sort.field == SortField.DATE_TAKEN) {
                choose(SortField.DATE_TAKEN, sort.direction)
            }
            CheckableItem(stringResource(R.string.sort_by_date_added), sort.field == SortField.DATE_ADDED) {
                choose(SortField.DATE_ADDED, sort.direction)
            }
            HorizontalDivider()
            CheckableItem(stringResource(R.string.sort_newest_first), sort.direction == SortDirection.NEWEST_FIRST) {
                choose(sort.field, SortDirection.NEWEST_FIRST)
            }
            CheckableItem(stringResource(R.string.sort_oldest_first), sort.direction == SortDirection.OLDEST_FIRST) {
                choose(sort.field, SortDirection.OLDEST_FIRST)
            }
        }
    }
}

@Composable
private fun AlbumOverflow(menu: AlbumMenuActions) {
    OverflowMenu { close ->
        MenuItem(R.string.album_rename) { close(); menu.onRename() }
        MenuItem(R.string.album_delete) { close(); menu.onDelete() }
    }
}

@Composable
private fun PersonOverflow(menu: PersonMenuActions) {
    OverflowMenu { close ->
        MenuItem(if (menu.hasName) R.string.person_rename else R.string.person_add_name) { close(); menu.onRename() }
        MenuItem(if (menu.isFavorite) R.string.person_unfavorite else R.string.person_favorite) { close(); menu.onToggleFavorite() }
        MenuItem(if (menu.isHidden) R.string.person_unhide else R.string.person_hide) { close(); menu.onToggleHidden() }
        MenuItem(R.string.person_merge) { close(); menu.onMerge() }
    }
}

@Composable
private fun SettingsItem(onClick: () -> Unit) = MenuItem(R.string.menu_settings, onClick)

/** A "more options" button whose menu content receives a `close` function. */
@Composable
private fun OverflowMenu(content: @Composable (close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}

@Composable
private fun MenuItem(@StringRes label: Int, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = onClick)
}

@Composable
private fun MenuHeader(@StringRes label: Int) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun CheckableItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = {
            if (checked) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp))
            } else {
                Box(Modifier.size(20.dp))
            }
        },
    )
}
