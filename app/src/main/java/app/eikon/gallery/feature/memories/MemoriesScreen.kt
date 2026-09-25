package app.eikon.gallery.feature.memories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.data.memories.MemoryCard
import app.eikon.gallery.domain.memories.MemoryId

/** What eikon remembers for today: anniversaries, trips, seasons and people, each a story you can play. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesScreen(
    onBack: () -> Unit,
    onPlay: (MemoryId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MemoriesViewModel = hiltViewModel(),
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    var menuOpen by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.memories_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu_more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.memory_reset)) }, onClick = { menuOpen = false; viewModel.reset() })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        val list = cards
        when {
            list == null -> Box(Modifier.padding(inner).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(
                stringResource(R.string.memories_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(inner).padding(horizontal = 32.dp, vertical = 48.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(top = inner.calculateTopPadding() + 8.dp, bottom = inner.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "note") { Text(stringResource(R.string.memories_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(list, key = { it.memory.id.toArg() }) { card -> MemoryCardView(card, viewModel, onPlay) }
            }
        }
    }
}

@Composable
private fun MemoryCardView(card: MemoryCard, viewModel: MemoriesViewModel, onPlay: (MemoryId) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val id = card.memory.id
    Box(
        Modifier.fillMaxWidth().aspectRatio(CARD_RATIO).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onPlay(id) },
    ) {
        card.cover?.let { MediaThumbnail(it, Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(memoryTitle(id, card.personName), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 2)
            val subtitle = memorySubtitle(id)
            if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
            Text(pluralStringResource(R.plurals.items_count, card.memory.itemCount, card.memory.itemCount), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.8f))
        }
        Box(Modifier.align(Alignment.TopEnd)) {
            IconButton(onClick = { menuOpen = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu_more), tint = Color.White) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MemoryMenuItems(id, card.personName, onDismiss = { menuOpen = false }, actions = viewModel)
            }
        }
    }
}

private const val CARD_RATIO = 16f / 10f

/** The choices a memory offers, in its card and in its player. */
@Composable
fun MemoryMenuItems(id: MemoryId, personName: String?, onDismiss: () -> Unit, actions: MemoryActions) {
    DropdownMenuItem(text = { Text(stringResource(R.string.memory_hide)) }, onClick = { onDismiss(); actions.hide(id) })
    DropdownMenuItem(text = { Text(stringResource(R.string.memory_show_fewer)) }, onClick = { onDismiss(); actions.showFewer(id) })
    val person = id.personId
    if (person != null) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.memory_show_less_of, personName ?: stringResource(R.string.person_unnamed))) },
            onClick = { onDismiss(); actions.showLessOf(person) },
        )
    }
}

/** What the user can tell Memories. */
interface MemoryActions {
    fun hide(id: MemoryId)
    fun showFewer(id: MemoryId)
    fun showLessOf(personId: Long)
}
