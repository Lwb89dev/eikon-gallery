package app.eikon.gallery.feature.viewer

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem as PlayerMediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import app.eikon.gallery.R
import app.eikon.gallery.domain.MediaItem

/**
 * Reads a [DataSource.Factory] as if the resource named by a uri started [offset] bytes later than it really does: exactly what a Motion
 * Photo needs, since its clip is an ordinary MP4 appended after the JPEG (see [app.eikon.gallery.domain.MotionPhotoDetector]).
 * `DataSpec.uriPositionOffset` is Media3's own mechanism for this (where in the *real* resource position 0 of what is served begins), so
 * nothing here decodes or copies anything; every read still goes straight to the file.
 */
@OptIn(UnstableApi::class)
private class MotionClipDataSourceFactory(private val base: DataSource.Factory, private val offset: Long) : DataSource.Factory {
    override fun createDataSource(): DataSource = MotionClipDataSource(base.createDataSource(), offset)
}

@OptIn(UnstableApi::class)
private class MotionClipDataSource(private val delegate: DataSource, private val offset: Long) : DataSource by delegate {
    override fun open(dataSpec: DataSpec): Long =
        delegate.open(dataSpec.buildUpon().setUriPositionOffset(dataSpec.uriPositionOffset + offset).build())
}

/**
 * The moving part of a Motion Photo: plays once, muted, in place of the still image, then leaves a small button to play it again. Shown
 * only for the original look of the photo (an edit is drawn over the still image, not over the clip, so a photo shown edited never plays
 * its motion; toggling to "original" brings it back). Drawn as one more layer of [LayeredImage], it is panned and zoomed along with
 * the rest of the picture without knowing anything about that itself.
 */
@Composable
fun MotionPhotoLayer(item: MediaItem, offset: Long) {
    var finished by remember(item.id) { mutableStateOf(false) }
    var generation by remember(item.id) { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        if (!finished) {
            PlayingMotionClip(item, offset, generation, onFinished = { finished = true })
        } else {
            ReplayButton(Modifier.align(Alignment.BottomStart)) {
                finished = false
                generation++
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayingMotionClip(item: MediaItem, offset: Long, generation: Int, onFinished: () -> Unit) {
    val context = LocalContext.current
    val player = remember(item.id, generation) { createMotionPlayer(context, item, offset) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onFinished()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    ContentFrame(player = player, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW, contentScale = ContentScale.Fit)
}

@OptIn(UnstableApi::class)
private fun createMotionPlayer(context: Context, item: MediaItem, offset: Long): ExoPlayer {
    val dataSourceFactory = MotionClipDataSourceFactory(DefaultDataSource.Factory(context), offset)
    val source = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(PlayerMediaItem.fromUri(item.uri))
    return ExoPlayer.Builder(context).build().apply {
        volume = 0f // the clip is muted, like a Live Photo; nothing here asks for audio focus
        setMediaSource(source)
        prepare()
        playWhenReady = true
    }
}

@Composable
private fun ReplayButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painterResource(R.drawable.ic_motion_photo),
            contentDescription = stringResource(R.string.viewer_motion_photo_replay),
            tint = Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}
