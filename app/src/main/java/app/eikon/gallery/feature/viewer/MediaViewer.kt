package app.eikon.gallery.feature.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.activity.compose.BackHandler
import app.eikon.gallery.R
import app.eikon.gallery.core.image.LocalEditRecipeTexts
import app.eikon.gallery.core.ui.ImmersiveMode
import app.eikon.gallery.core.ui.SystemBarIcons
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.edit.EditRecipeCodec
import kotlin.math.abs
import kotlinx.coroutines.launch

private val BottomBarHeight = 64.dp
private val DismissThreshold = 110.dp
private const val MAX_PULL_BACKDROP_FADE = 0.75f
private const val MAX_PULL_SHRINK = 0.12f

/**
 * Full-screen viewer over the same paged list as the grid, so swiping walks the whole library
 * (however large) without ever holding more than a few items.
 *
 * Gestures: swipe left/right between items, pinch/double-tap to zoom, tap to toggle the chrome and
 * system bars, drag down to close, drag up (or the info button) for the details sheet.
 */
@Composable
fun MediaViewer(
    items: ViewerItems,
    initialPage: Int,
    onPageChanged: (Int) -> Unit,
    onClose: () -> Unit,
    leadingActions: List<ViewerAction>,
    trailingActions: List<ViewerAction>,
    infoSheet: @Composable (item: MediaItem, onDismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = items.count
    LaunchedEffect(count == 0) { if (count == 0) onClose() }

    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, (count - 1).coerceAtLeast(0))) { items.count }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var infoOpen by rememberSaveable { mutableStateOf(false) }
    var zoomed by remember { mutableStateOf(false) }
    val pull = rememberPullState()
    val currentItem = items.peek(pagerState.currentPage)
    val edits = LocalEditRecipeTexts.current
    // The photo being looked at without its edit; going to another photo puts the edits back.
    var originalShownFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val editOf = { item: MediaItem -> if (item.isVideo || item.id == originalShownFor) null else edits[item.id] }

    LaunchedEffect(pagerState) { snapshotFlow { pagerState.currentPage }.collect(onPageChanged) }
    LaunchedEffect(pagerState.currentPage) {
        zoomed = false
        originalShownFor = null
    }
    BackHandler(onBack = onClose)
    ImmersiveMode(hidden = !chromeVisible)
    SystemBarIcons(lightIcons = true)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - MAX_PULL_BACKDROP_FADE * pull.progress))
            .pullGestures(pull, enabled = !zoomed, onDismiss = onClose, onPullUp = { infoOpen = true }),
    ) {
        ViewerPager(
            pagerState = pagerState,
            items = items,
            pull = pull,
            chromeVisible = chromeVisible,
            editOf = editOf,
            onToggleChrome = { chromeVisible = !chromeVisible },
            onZoomedChange = { zoomed = it },
        )
        ViewerChrome(
            visible = chromeVisible && pull.offset == 0f,
            position = pagerState.currentPage + 1,
            count = count,
            item = currentItem,
            onClose = onClose,
            leadingActions = leadingActions,
            trailingActions = trailingActions,
            onInfo = { infoOpen = true },
            editedChip = currentItem?.takeIf { !it.isVideo && it.id in edits }?.let { item ->
                { EditedChip(showingOriginal = item.id == originalShownFor) { originalShownFor = if (item.id == originalShownFor) null else item.id } }
            },
        )
    }
    if (infoOpen && currentItem != null) infoSheet(currentItem) { infoOpen = false }
}

@Composable
private fun ViewerPager(
    pagerState: PagerState,
    items: ViewerItems,
    pull: PullState,
    chromeVisible: Boolean,
    editOf: (MediaItem) -> String?,
    onToggleChrome: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
) {
    val controlsPadding = BottomBarHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = pull.offset
                val shrink = 1f - MAX_PULL_SHRINK * pull.progress
                scaleX = shrink
                scaleY = shrink
            },
        beyondViewportPageCount = 1,
        pageSpacing = 16.dp,
        key = { index -> items.peek(index)?.id ?: "p$index" },
    ) { page ->
        val item = items.get(page)
        if (item != null) {
            ViewerPage(
                item = item,
                position = page + 1,
                count = items.count,
                isCurrent = page == pagerState.currentPage,
                chromeVisible = chromeVisible,
                controlsPadding = controlsPadding,
                editText = editOf(item),
                onTap = onToggleChrome,
                onZoomedChange = onZoomedChange,
            )
        }
    }
}

@Composable
private fun ViewerPage(
    item: MediaItem,
    position: Int,
    count: Int,
    isCurrent: Boolean,
    chromeVisible: Boolean,
    controlsPadding: Dp,
    editText: String?,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
) {
    val recipe = remember(editText) { editText?.let(EditRecipeCodec::decode)?.takeUnless { it.isIdentity } }
    val label = "${item.displayName}, ${stringResource(R.string.viewer_position, position, count)}"
    Box(Modifier.fillMaxSize().semantics { contentDescription = label }) {
        if (item.isVideo) {
            VideoPage(item, isCurrent, chromeVisible, controlsPadding, onTap)
        } else {
            ImagePage(item, isCurrent, onTap, onZoomedChange, recipe = recipe)
        }
    }
}

// --- Chrome ------------------------------------------------------------------------------------

@Composable
private fun ViewerChrome(
    visible: Boolean,
    position: Int,
    count: Int,
    item: MediaItem?,
    onClose: () -> Unit,
    leadingActions: List<ViewerAction>,
    trailingActions: List<ViewerAction>,
    onInfo: () -> Unit,
    editedChip: (@Composable () -> Unit)?,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, Modifier.align(Alignment.TopStart), enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(position, count, onClose, editedChip)
        }
        AnimatedVisibility(visible && item != null, Modifier.align(Alignment.BottomStart), enter = fadeIn(), exit = fadeOut()) {
            if (item != null) ViewerActionBar(item, leadingActions, trailingActions, onInfo)
        }
    }
}

private val ChromeBackground = Color.Black.copy(alpha = 0.45f)

@Composable
private fun ViewerTopBar(position: Int, count: Int, onClose: () -> Unit, editedChip: (@Composable () -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChromeBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back), tint = Color.White)
        }
        Text(
            text = stringResource(R.string.viewer_position, position, count),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp).weight(1f),
        )
        editedChip?.invoke()
    }
}

/** Says the photo is shown edited and, when tapped, shows it as it was taken (and back). Nothing is changed by tapping it. */
@Composable
private fun EditedChip(showingOriginal: Boolean, onToggle: () -> Unit) {
    val label = stringResource(if (showingOriginal) R.string.edit_original else R.string.edited_badge)
    val action = stringResource(if (showingOriginal) R.string.viewer_show_edited else R.string.viewer_show_original)
    Row(
        modifier = Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.18f))
            .clickable(onClickLabel = action, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_edit), contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ViewerActionBar(
    item: MediaItem,
    leadingActions: List<ViewerAction>,
    trailingActions: List<ViewerAction>,
    onInfo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChromeBackground)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(BottomBarHeight),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingActions.filter { it.visible(item) }.forEach { ActionButton(it.icon(item), it.label(item)) { it.onClick(item) } }
        ActionButton(R.drawable.ic_info, R.string.action_info, onInfo)
        trailingActions.filter { it.visible(item) }.forEach { ActionButton(it.icon(item), it.label(item)) { it.onClick(item) } }
    }
}

@Composable
private fun ActionButton(icon: Int, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(icon), stringResource(label), tint = Color.White)
    }
}

// --- Vertical pull: down closes, up opens details -------------------------------------------------

/** How far the content is currently pulled vertically, and how that maps to fading/shrinking. */
private class PullState(val thresholdPx: Float) {
    var offset by mutableFloatStateOf(0f)

    /** 0 at rest, 1 at twice the dismiss threshold. */
    val progress: Float get() = (abs(offset) / (thresholdPx * 2f)).coerceIn(0f, 1f)

    suspend fun settle() {
        animate(offset, 0f) { value, _ -> offset = value }
    }
}

@Composable
private fun rememberPullState(): PullState {
    val thresholdPx = with(LocalDensity.current) { DismissThreshold.toPx() }
    return remember(thresholdPx) { PullState(thresholdPx) }
}

/**
 * Down past the threshold closes (the content stays where it was let go while the viewer fades out),
 * up past it opens the details; anything shorter springs back.
 */
@Composable
private fun Modifier.pullGestures(
    pull: PullState,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onPullUp: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    if (!enabled) return this
    return pointerInput(pull) {
        detectVerticalDragGestures(
            onVerticalDrag = { change, delta ->
                change.consume()
                pull.offset += delta
            },
            onDragEnd = {
                if (pull.offset > pull.thresholdPx) {
                    onDismiss()
                } else {
                    if (pull.offset < -pull.thresholdPx) onPullUp()
                    scope.launch { pull.settle() }
                }
            },
            onDragCancel = { scope.launch { pull.settle() } },
        )
    }
}
