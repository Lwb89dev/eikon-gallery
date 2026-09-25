package app.eikon.gallery.feature.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.core.ui.SystemBarIcons
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize

/**
 * When another app asks eikon to show a picture ("Open with"). Only pictures that arrive as `content://` addresses with an image type are taken:
 * the sender grants access to that one picture, which is all eikon reads. It is not added to the library, not analyzed, not remembered.
 */
object ExternalView {
    fun isImage(action: String?, scheme: String?, mimeType: String?): Boolean =
        action == ACTION_VIEW && scheme == "content" && mimeType?.startsWith("image/", ignoreCase = true) == true

    private const val ACTION_VIEW = "android.intent.action.VIEW"
}

/** One picture from another app, on black, with pinch and double-tap zoom. Back closes it (and so returns to the app that asked). */
@Composable
fun ExternalImageViewer(uri: Uri, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val state = remember(uri) { ZoomState() }
    var failed by remember(uri) { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    SystemBarIcons(lightIcons = true)
    Box(modifier.fillMaxSize().background(Color.Black)) {
        ZoomableBox(state, onTap = {}) { ExternalLayers(uri, state) { failed = true } }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(4.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
        ) {
            Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back), tint = Color.White)
        }
        if (failed) CannotDisplay(Modifier.align(Alignment.Center))
    }
}

@Composable
private fun ExternalLayers(uri: Uri, state: ZoomState, onError: () -> Unit) {
    val context = LocalPlatformContext.current
    val container = state.containerSize
    if (container != IntSize.Zero) {
        val request = remember(uri, container) { ImageRequest.Builder(context).data(uri).size(CoilSize(container.width, container.height)).build() }
        AsyncImage(
            model = request,
            contentDescription = stringResource(R.string.external_photo),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { state.imageAspect = it.result.image.width.toFloat() / it.result.image.height },
            onError = { onError() },
        )
    }
    if (state.isZoomed) {
        val large = remember(uri) { ImageRequest.Builder(context).data(uri).size(CoilSize(HIGH_RES_EDGE_PX, HIGH_RES_EDGE_PX)).build() }
        AsyncImage(model = large, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/** Said in words, so a photo that will not open is not just a black screen. */
@Composable
fun CannotDisplay(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.viewer_cannot_display),
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = modifier.padding(32.dp),
    )
}
