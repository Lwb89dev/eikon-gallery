package app.eikon.gallery.feature.viewer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
 * When another app asks eikon to show a picture ("Open with", or a camera showing the photo it just took). Only pictures that arrive as `content://` addresses with an image
 * type are taken: the sender grants access to that one picture, which is all eikon reads. It is not added to the library, not analyzed, not remembered.
 */
object ExternalView {
    /** The ordinary "view", and the two "review" actions that camera apps use for "show me the picture I just took". */
    private val ACTIONS = setOf("android.intent.action.VIEW", "com.android.camera.action.REVIEW", "android.provider.action.REVIEW")

    private const val MEDIA_AUTHORITY = "media"
    private const val MEDIA_STORE_SEGMENTS = 4

    /**
     * [mediaId] is the picture's id when its address is a MediaStore one (see [mediaStoreId]): cameras often leave the type out for those, and the address itself says it is an image.
     */
    fun isImage(action: String?, scheme: String?, mimeType: String?, mediaId: Long? = null): Boolean {
        if (action !in ACTIONS || scheme != "content") return false
        return mimeType?.startsWith("image/", ignoreCase = true) == true || (mimeType == null && mediaId != null)
    }

    /** The id of a picture from an address like `content://media/external/images/media/1234`, or null for any other kind of address. */
    fun mediaStoreId(authority: String?, segments: List<String>): Long? {
        val isImageAddress = authority == MEDIA_AUTHORITY && segments.size == MEDIA_STORE_SEGMENTS && segments[1] == "images" && segments[2] == "media"
        return if (isImageAddress) segments[MEDIA_STORE_SEGMENTS - 1].toLongOrNull() else null
    }
}

/**
 * One picture from another app, on black, with pinch and double-tap zoom. Back closes it (and so returns to the app that asked). When the picture is one of the phone's own
 * (its address is a MediaStore one, like the photo a camera has just taken) [onOpenInLibrary] adds a button that goes on to the library, at that photo.
 */
@Composable
fun ExternalImageViewer(uri: Uri, onClose: () -> Unit, modifier: Modifier = Modifier, onOpenInLibrary: ((mediaId: Long) -> Unit)? = null) {
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
        val mediaId = remember(uri) { ExternalView.mediaStoreId(uri.authority, uri.pathSegments) }
        if (mediaId != null && onOpenInLibrary != null) {
            OpenInLibraryButton(onClick = { onOpenInLibrary(mediaId) }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp))
        }
        if (failed) CannotDisplay(Modifier.align(Alignment.Center))
    }
}

/** A dark pill with white text, readable on any picture, that leaves this single picture for the library. */
@Composable
private fun OpenInLibraryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_photo), contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.viewer_open_in_library), color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
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
