package app.eikon.gallery.feature.library

import android.content.Context
import android.content.res.Resources
import android.text.format.DateUtils
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.core.image.LocalEditRecipeTexts
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.core.ui.theme.SectionHeaderStyle
import app.eikon.gallery.domain.ExifFormat
import app.eikon.gallery.domain.MediaItem

private val SelectedInset = 10.dp
private val BadgeBackground = Color.Black.copy(alpha = 0.5f)

@Composable
fun SectionHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = SectionHeaderStyle,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/**
 * One grid cell. [item] is null while its page is still loading from the index: an empty tile keeps
 * the grid geometry stable so scrolling never jumps.
 *
 * [thumbnailSize], when given, is the size the picture is asked for instead of the size of the cell (see ThumbnailSizes).
 *
 * Everything here runs for every cell that scrolls into view, so it is kept cheap: the spoken
 * description is built inside the semantics block (evaluated only when an accessibility service reads
 * it), and selection visuals cost nothing outside selection mode.
 */
@Composable
fun MediaCell(
    item: MediaItem?,
    selected: Boolean,
    selectionMode: Boolean,
    selectLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnailSize: IntSize? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val placeholder = MaterialTheme.colorScheme.surfaceContainer
    val context = LocalContext.current
    val resources = LocalResources.current
    val interaction = Modifier.cellInteraction(item, selected, selectLabel, context, resources, onClick, onLongClick)
    Box(modifier.aspectRatio(1f).background(placeholder).then(interaction)) {
        if (item != null) {
            CellContent(item, selected, selectionMode, thumbnailSize)
            overlay()
        }
    }
}

/** What a filled cell answers to: a tap, a long press, and the words a screen reader speaks for it. An empty (still loading) cell answers to nothing. */
private fun Modifier.cellInteraction(
    item: MediaItem?,
    selected: Boolean,
    selectLabel: String,
    context: Context,
    resources: Resources,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier {
    if (item == null) return this
    return clickable(onClick = onClick).semantics {
        contentDescription = accessibilityLabel(context, resources, item)
        this.selected = selected
        onLongClick(label = selectLabel) {
            onLongClick()
            true
        }
    }
}

@Composable
private fun BoxScope.CellContent(item: MediaItem, selected: Boolean, selectionMode: Boolean, thumbnailSize: IntSize?) {
    // The animation and rounded clip exist only in selection mode; the thumbnail composable itself
    // stays in the same place so entering selection never reloads it.
    val inset = if (selectionMode) {
        animateDpAsState(if (selected) SelectedInset else 0.dp, label = "selection inset").value
    } else {
        0.dp
    }
    val thumbnailModifier = Modifier.fillMaxSize().padding(inset)
    MediaThumbnail(
        item = item,
        modifier = if (selectionMode) thumbnailModifier.clip(RoundedCornerShape(if (selected) 6.dp else 0.dp)) else thumbnailModifier,
        requestSize = thumbnailSize,
    )
    if (item.isVideo) VideoBadge(item.durationMs, Modifier.align(Alignment.BottomEnd))
    if (item.isFavorite) FavoriteBadge(Modifier.align(Alignment.BottomStart))
    if (!item.isVideo && item.id in LocalEditRecipeTexts.current) EditedBadge(Modifier.align(Alignment.TopEnd))
    if (selectionMode) SelectionIndicator(selected, Modifier.align(Alignment.TopStart))
}

@Composable
private fun VideoBadge(durationMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(4.dp)
            .background(BadgeBackground, RoundedCornerShape(50))
            .padding(start = 3.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_play_arrow), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(ExifFormat.duration(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FavoriteBadge(modifier: Modifier = Modifier) {
    Box(modifier.padding(4.dp).size(20.dp).background(BadgeBackground, CircleShape), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_favorite),
            contentDescription = stringResource(R.string.cd_favorite_badge),
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun EditedBadge(modifier: Modifier = Modifier) {
    Box(modifier.padding(4.dp).size(20.dp).background(BadgeBackground, CircleShape), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_edit),
            contentDescription = stringResource(R.string.cd_edited_badge),
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun SelectionIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.padding(6.dp).size(24.dp), contentAlignment = Alignment.Center) {
        if (selected) {
            Box(Modifier.size(18.dp).background(Color.White, CircleShape))
            Icon(
                painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Icon(painterResource(R.drawable.ic_radio_unchecked), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

private fun accessibilityLabel(context: Context, resources: Resources, item: MediaItem): String {
    val date = DateUtils.formatDateTime(context, item.takenAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
    return if (item.isVideo) {
        resources.getString(R.string.cd_video, date, ExifFormat.duration(item.durationMs))
    } else {
        resources.getString(R.string.cd_photo, date)
    }
}
