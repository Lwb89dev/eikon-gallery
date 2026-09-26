package app.eikon.gallery.feature.viewer

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.state.rememberMuteButtonState
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import app.eikon.gallery.R
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.domain.ExifFormat
import app.eikon.gallery.domain.MediaItem
import androidx.media3.common.MediaItem as PlayerMediaItem

private const val PROGRESS_TICK_MS = 250L

/**
 * A video page. Only the page on screen owns a player (created on arrival, released on leaving), so
 * swiping through a run of videos never holds more than one decoder. The neighbours show their
 * thumbnail. Playback starts automatically and pauses when the app goes to the background. The picture
 * zooms like a photo does (pinch, double tap, drag); the controls stay where they are.
 */
@Composable
fun VideoPage(
    item: MediaItem,
    isCurrent: Boolean,
    controlsVisible: Boolean,
    controlsBottomPadding: Dp,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isCurrent) {
        PlayingVideo(item, controlsVisible, controlsBottomPadding, onTap, onZoomedChange, modifier)
        return
    }
    Box(modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        MediaThumbnail(item, Modifier.fillMaxSize(), ContentScale.Fit)
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayingVideo(
    item: MediaItem,
    controlsVisible: Boolean,
    controlsBottomPadding: Dp,
    onTap: () -> Unit,
    onZoomedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val player = remember(item.id) { createPlayer(context, item) }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    val zoom = remember(item.id) { ZoomState() }
    LaunchedEffect(zoom) { snapshotFlow { zoom.isZoomed }.collect(onZoomedChange) }
    TrackVideoShape(player, zoom)

    Box(modifier.fillMaxSize()) {
        ZoomableBox(zoom, onTap) {
            // A texture, not the default surface: a surface is not clipped to the zoomed picture's window and would spill over the controls and the next page.
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = ContentScale.Fit,
                shutter = { MediaThumbnail(item, Modifier.fillMaxSize(), ContentScale.Fit) },
            )
        }
        if (controlsVisible) {
            VideoControls(player, Modifier.align(Alignment.BottomCenter).padding(bottom = controlsBottomPadding))
        }
    }
}

/** Tells [zoom] how wide the picture is once the player knows, so a zoomed video cannot be dragged off its edges. */
@Composable
private fun TrackVideoShape(player: Player, zoom: ZoomState) {
    DisposableEffect(player, zoom) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                zoom.imageAspect = VideoAspect.of(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }
        }
        player.addListener(listener)
        listener.onVideoSizeChanged(player.videoSize)
        onDispose { player.removeListener(listener) }
    }
}

@OptIn(UnstableApi::class)
private fun createPlayer(context: Context, item: MediaItem): ExoPlayer {
    val audio = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()
    return ExoPlayer.Builder(context)
        .setAudioAttributes(audio, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)
        .build()
        .apply {
            setMediaItem(PlayerMediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
}

/** The player as [Scrubber] drives it: through its scrubbing mode, which answers seeks as fast as it can (and shows frames on the way) instead of one at a time. */
@OptIn(UnstableApi::class)
private class PlayerScrubTarget(private val player: ExoPlayer) : ScrubTarget {
    override var playWhenReady: Boolean
        get() = player.playWhenReady
        set(value) {
            player.playWhenReady = value
        }

    override fun setScrubbing(enabled: Boolean) = player.setScrubbingModeEnabled(enabled)

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
}

/** Play/pause, seek bar with times, and mute; all state comes from the player through Media3's Compose state holders. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoControls(player: ExoPlayer, modifier: Modifier = Modifier) {
    val playPause = rememberPlayPauseButtonState(player)
    val mute = rememberMuteButtonState(player)
    val progress = rememberProgressStateWithTickInterval(player, PROGRESS_TICK_MS)
    val scrubber = remember(player) { Scrubber(PlayerScrubTarget(player)) }
    DisposableEffect(scrubber) { onDispose { scrubber.cancel() } }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    val duration = progress.durationMs.takeIf { it > 0 } ?: 0L
    val fraction = scrubbing ?: if (duration > 0) progress.currentPositionMs.toFloat() / duration else 0f

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = playPause::onClick, enabled = playPause.isEnabled) {
            val icon = if (playPause.showPlay) R.drawable.ic_play_arrow else R.drawable.ic_pause
            val label = if (playPause.showPlay) R.string.video_play else R.string.video_pause
            Icon(painterResource(icon), stringResource(label), tint = Color.White)
        }
        TimeText(if (duration > 0) (fraction * duration).toLong() else progress.currentPositionMs)
        SeekBar(
            fraction = fraction,
            enabled = duration > 0,
            onScrub = {
                scrubbing = it
                scrubber.move((it * duration).toLong())
            },
            onScrubFinished = {
                scrubbing?.let { scrubber.finish((it * duration).toLong()) }
                scrubbing = null
            },
            modifier = Modifier.weight(1f),
        )
        TimeText(duration)
        IconButton(onClick = mute::onClick, enabled = mute.isEnabled) {
            val icon = if (mute.showMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up
            val label = if (mute.showMuted) R.string.video_unmute else R.string.video_mute
            Icon(painterResource(icon), stringResource(label), tint = Color.White)
        }
    }
}

@Composable
private fun TimeText(millis: Long) {
    Text(
        text = ExifFormat.duration(millis),
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.width(44.dp),
    )
}

@Composable
private fun SeekBar(
    fraction: Float,
    enabled: Boolean,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.video_seek)
    Slider(
        value = fraction.coerceIn(0f, 1f),
        onValueChange = onScrub,
        onValueChangeFinished = onScrubFinished,
        enabled = enabled,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = Color.White,
            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            disabledThumbColor = Color.White.copy(alpha = 0.4f),
            disabledActiveTrackColor = Color.White.copy(alpha = 0.3f),
            disabledInactiveTrackColor = Color.White.copy(alpha = 0.2f),
        ),
        modifier = modifier.padding(horizontal = 4.dp).semantics { contentDescription = description },
    )
}
