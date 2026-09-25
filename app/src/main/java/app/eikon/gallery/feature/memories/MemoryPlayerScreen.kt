package app.eikon.gallery.feature.memories

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.memories.MemoryId
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * A memory as a slideshow: up to 30 of its photos, each shown for a few seconds with a slow zoom and drift (the Ken Burns effect)
 * and a cross-fade to the next. Hold to pause, tap the sides to go back or forward. There is no music.
 */
@Composable
fun MemoryPlayerScreen(
    onClose: () -> Unit,
    onOpenAll: (MemoryId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MemoryPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize().background(Color.Black)) {
        val id = state.id
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            id == null || state.photos.isEmpty() -> EmptyPlayer(onClose)
            else -> Slideshow(id, state, viewModel, onClose, onOpenAll)
        }
    }
}

@Composable
private fun EmptyPlayer(onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.memory_no_photos), color = Color.White, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onClose) { Text(stringResource(R.string.action_back), color = Color.White) }
    }
}

@Composable
private fun Slideshow(id: MemoryId, state: PlayerState, viewModel: MemoryPlayerViewModel, onClose: () -> Unit, onOpenAll: (MemoryId) -> Unit) {
    val photos = state.photos
    var index by remember { mutableIntStateOf(0) }
    var held by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val done = stringResource(R.string.memory_done)

    // One clock drives both the progress bar and the slow zoom: it runs while nothing is held, then moves to the next photo.
    LaunchedEffect(index, held, finished) {
        if (held || finished) return@LaunchedEffect
        progress.animateTo(1f, tween(durationMillis = ((1f - progress.value) * SLIDE_MILLIS).toInt().coerceAtLeast(1), easing = LinearEasing))
        if (index < photos.lastIndex) {
            index++
            progress.snapTo(0f)
        } else {
            finished = true
        }
    }
    fun go(to: Int) {
        index = to.coerceIn(0, photos.lastIndex)
        finished = false
        scope.launch { progress.snapTo(0f) }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(photos.size) {
            detectTapGestures(
                onPress = {
                    held = true
                    tryAwaitRelease()
                    held = false
                },
                onTap = { at -> if (at.x < size.width / SIDE_FRACTION) go(index - 1) else go(index + 1) },
            )
        },
    ) {
        Crossfade(targetState = index, animationSpec = tween(FADE_MILLIS), label = "slide") { i ->
            KenBurns(photos[i], i, if (i == index) progress.value else 1f)
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                photos.indices.forEach { i ->
                    val fraction = when {
                        i < index || finished -> 1f
                        i == index -> progress.value
                        else -> 0f
                    }
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f).height(3.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_back), tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(memoryTitle(id, state.personName), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    val subtitle = memorySubtitle(id)
                    if (subtitle.isNotEmpty()) Text(subtitle, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
                }
                PlayerMenu(id, state.personName, photos[index], viewModel, onClose, onOpenAll) { scope.launch { snackbar.showSnackbar(done) } }
            }
        }
        if (finished) {
            Button(onClick = { go(0) }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)) { Text(stringResource(R.string.memory_replay)) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}

/** A full-screen photo that slowly grows and drifts; every other photo drifts the opposite way so the show does not feel mechanical. */
@Composable
private fun KenBurns(item: MediaItem, position: Int, progress: Float) {
    val direction = if (position % 2 == 0) 1f else -1f
    AsyncImage(
        model = item.uri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().graphicsLayer {
            val scale = ZOOM_START + (ZOOM_END - ZOOM_START) * progress
            scaleX = scale
            scaleY = scale
            translationX = direction * size.width * DRIFT * (progress - 0.5f)
            translationY = -direction * size.height * DRIFT * (progress - 0.5f)
        },
    )
}

@Composable
private fun PlayerMenu(
    id: MemoryId,
    personName: String?,
    current: MediaItem,
    viewModel: MemoryPlayerViewModel,
    onClose: () -> Unit,
    onOpenAll: (MemoryId) -> Unit,
    onChosen: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.menu_more), tint = Color.White) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.memory_all_photos)) }, onClick = { open = false; onOpenAll(id) })
            MemoryMenuItems(id, personName, onDismiss = { open = false }, actions = object : MemoryActions {
                override fun hide(id: MemoryId) { viewModel.hide(); onClose() }
                override fun showFewer(id: MemoryId) { viewModel.showFewer(); onChosen() }
                override fun showLessOf(personId: Long) { viewModel.showLessOf(personId); onChosen() }
            })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.memory_exclude_date)) },
                onClick = { open = false; viewModel.excludeDay(Instant.ofEpochMilli(current.takenAt).atZone(ZoneId.systemDefault()).toLocalDate()); onChosen() },
            )
        }
    }
}

private const val SLIDE_MILLIS = 4_500
private const val FADE_MILLIS = 600
private const val SIDE_FRACTION = 3
private const val ZOOM_START = 1.02f
private const val ZOOM_END = 1.16f
private const val DRIFT = 0.04f
