package app.eikon.gallery.feature.people

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import app.eikon.gallery.core.image.MediaThumbnailData
import app.eikon.gallery.data.db.PersonSummary
import app.eikon.gallery.domain.mediaContentUri
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Size
import kotlin.math.max

/** A face, as fractions of the upright picture it was found in. */
class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * A round picture of one face: the photo it was found in, zoomed and moved so the face fills most of the circle.
 * The thumbnail is loaded through the same system thumbnail cache as the grid.
 */
@Composable
fun FaceAvatar(mediaId: Long, modifiedAt: Long, box: FaceBox, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(mediaId, modifiedAt) {
        ImageRequest.Builder(context)
            .data(MediaThumbnailData(mediaContentUri(mediaId, isVideo = false), modifiedAt))
            .size(Size(THUMBNAIL_EDGE, THUMBNAIL_EDGE))
            .build()
    }
    val painter = rememberAsyncImagePainter(request)
    Canvas(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer)) {
        val picture = painter.intrinsicSize
        if (!picture.isSpecified || picture.width <= 0f) return@Canvas
        val faceWidth = (box.right - box.left) * picture.width
        val faceHeight = (box.bottom - box.top) * picture.height
        val zoom = size.minDimension * FACE_FILL / max(faceWidth, faceHeight)
        val centreX = (box.left + box.right) / 2 * picture.width
        val centreY = (box.top + box.bottom) / 2 * picture.height
        translate(left = size.width / 2 - centreX * zoom, top = size.height / 2 - centreY * zoom) {
            scale(zoom, zoom, pivot = Offset.Zero) { with(painter) { draw(picture) } }
        }
    }
}

/** The avatar of a person from the People list. */
@Composable
fun FaceAvatar(person: PersonSummary, modifier: Modifier = Modifier) = FaceAvatar(
    person.coverMediaId,
    person.coverModifiedAt,
    FaceBox(person.coverLeft, person.coverTop, person.coverRight, person.coverBottom),
    modifier,
)

private const val THUMBNAIL_EDGE = 512

/** How much of the circle the face itself takes, leaving room for hair and shoulders. */
private const val FACE_FILL = 0.55f
