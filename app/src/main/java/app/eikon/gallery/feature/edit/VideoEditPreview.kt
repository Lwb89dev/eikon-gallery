package app.eikon.gallery.feature.edit

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.eikon.gallery.data.edit.VideoEditEffects
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.edit.EditRecipe
import kotlinx.coroutines.delay
import androidx.media3.common.MediaItem as PlayerMediaItem

/** How long a slider has to sit still before the preview is rebuilt with its new value: enough that dragging never fights a reload for the frame it is drawing. */
private const val PREVIEW_DEBOUNCE_MS = 200L

/**
 * The video editor's own preview: [item] muted, looping inside [recipe]'s trim, with its color and (supported) geometry drawn live by
 * Media3's GPU effects (see [VideoEditEffects]) — nothing here decodes a frame on the CPU. Rebuilt, debounced, whenever [recipe] changes;
 * a fresh player each time rather than updating the live one, since changing a running effects pipeline mid-playback is not something this
 * project can check without a device (see [VideoEditEffects]'s own caveat on the effects themselves).
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoEditPreview(item: MediaItem, recipe: EditRecipe, modifier: Modifier = Modifier) {
    var settled by remember(item.id) { mutableStateOf(recipe) }
    LaunchedEffect(recipe) {
        delay(PREVIEW_DEBOUNCE_MS)
        settled = recipe
    }
    val context = LocalContext.current
    val player = remember(item.id, settled) { createPreviewPlayer(context, item, settled) }
    DisposableEffect(player) { onDispose { player.release() } }
    ContentFrame(player = player, modifier = modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW, contentScale = ContentScale.Fit)
}

@OptIn(UnstableApi::class)
private fun createPreviewPlayer(context: Context, item: MediaItem, recipe: EditRecipe): ExoPlayer {
    val mediaItem = PlayerMediaItem.Builder().setUri(item.uri).setClippingConfiguration(VideoEditEffects.clippingConfigOf(recipe.trim)).build()
    return ExoPlayer.Builder(context).build().apply {
        setVideoEffects(VideoEditEffects.forRecipe(recipe, item.width, item.height))
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 0f // the preview is silent while editing, like the still image's preview never plays a photo's own sound (it has none)
        setMediaItem(mediaItem)
        prepare()
        playWhenReady = true
    }
}
