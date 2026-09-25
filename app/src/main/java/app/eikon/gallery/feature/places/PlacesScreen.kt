package app.eikon.gallery.feature.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.data.places.PlaceKey
import app.eikon.gallery.data.places.PlaceNode
import app.eikon.gallery.domain.GridSource
import app.eikon.gallery.domain.LibraryScope

/** Where the photos were taken: a list by country, region and city, and a map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    onBack: () -> Unit,
    onOpen: (GridSource) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlacesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.places_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (state.tree.isEmpty()) {
                if (state.loaded) EmptyPlaces(state, onOpenSettings)
                return@Column
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.places_tab_list)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.places_tab_map)) })
            }
            if (tab == 0) PlacesList(state.tree, onOpen) else PlacesMapTab(viewModel, onOpen)
        }
    }
}

@Composable
private fun PlacesMapTab(viewModel: PlacesViewModel, onOpen: (GridSource) -> Unit) {
    val data by viewModel.map.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.loadMap() }
    val loaded = data
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
        return
    }
    Column(Modifier.fillMaxSize()) {
        PlacesMap(loaded, onOpenArea = { area -> onOpen(GridSource.Area(area)) }, Modifier.weight(1f))
        Text(
            text = stringResource(R.string.places_map_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun PlacesList(tree: List<PlaceNode>, onOpen: (GridSource) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val rows = flatten(tree, expanded)
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.id }) { row ->
            PlaceRow(
                row = row,
                onOpen = { onOpen(GridSource.Place(scopeOf(row.node.key))) },
                onToggle = { expanded = if (row.id in expanded) expanded - row.id else expanded + row.id },
            )
        }
    }
}

private class Row(val id: String, val node: PlaceNode, val depth: Int, val isOpen: Boolean)

/** The visible rows: a country's regions appear when it is expanded, a region's cities when it is. */
private fun flatten(tree: List<PlaceNode>, expanded: Set<String>): List<Row> = buildList {
    fun add(node: PlaceNode, depth: Int) {
        val id = idOf(node.key)
        val open = id in expanded
        add(Row(id, node, depth, open))
        if (open) node.children.forEach { add(it, depth + 1) }
    }
    tree.forEach { add(it, 0) }
}

private fun idOf(key: PlaceKey): String = when (key) {
    is PlaceKey.City -> "city:${key.id}"
    is PlaceKey.Region -> "region:${key.key}"
    is PlaceKey.Country -> "country:${key.code}"
    PlaceKey.Unknown -> "unknown"
}

private fun scopeOf(key: PlaceKey): LibraryScope.Place = when (key) {
    is PlaceKey.City -> LibraryScope.Place(city = key.id)
    is PlaceKey.Region -> LibraryScope.Place(region = key.key)
    is PlaceKey.Country -> LibraryScope.Place(country = key.code)
    PlaceKey.Unknown -> LibraryScope.Place(unknown = true)
}

@Composable
private fun PlaceRow(row: Row, onOpen: () -> Unit, onToggle: () -> Unit) {
    val node = row.node
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp + 20.dp * row.depth, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) {
            MediaThumbnail(node.cover.mediaId, false, node.cover.modifiedAt, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(node.title.ifEmpty { stringResource(R.string.place_unknown) }, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                pluralStringResource(R.plurals.items_count, node.photoCount, node.photoCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (node.children.isNotEmpty()) {
            TextButton(onClick = onToggle) { Text(stringResource(if (row.isOpen) R.string.places_collapse else R.string.places_expand)) }
        }
    }
}

/** Nothing to list: says why (off, still analysing, or no photo has a position) rather than showing a blank screen. */
@Composable
private fun EmptyPlaces(state: PlacesState, onOpenSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val progress = state.progress
        val message = when {
            !state.enabled -> stringResource(R.string.places_empty_off)
            progress != null && !progress.isComplete -> stringResource(R.string.places_empty_working, if (progress.total == 0) 0 else progress.done * 100 / progress.total)
            else -> stringResource(R.string.places_empty_done)
        }
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (!state.enabled) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.people_go_settings)) }
    }
}
