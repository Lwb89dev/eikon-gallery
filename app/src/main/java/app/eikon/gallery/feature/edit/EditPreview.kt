package app.eikon.gallery.feature.edit

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.domain.edit.Crop
import app.eikon.gallery.domain.edit.CropHandle
import app.eikon.gallery.domain.edit.CropTool
import kotlin.math.roundToInt

/** The photo as edited. Hold it (outside the crop tool) to see the original; in the crop tool the crop rectangle is drawn over it. */
@Composable
fun EditPreview(state: EditUiState, viewModel: EditViewModel, modifier: Modifier = Modifier) {
    var comparing by remember { mutableStateOf(false) }
    val cropping = state.tool == EditTool.CROP
    val shown = if (comparing && !cropping) state.original else state.preview
    Box(
        modifier.fillMaxSize().pointerInput(cropping) {
            if (!cropping) detectTapGestures(onPress = { comparing = true; tryAwaitRelease(); comparing = false })
        },
    ) {
        if (shown != null) PreviewPicture(shown, state, viewModel, cropping)
        if (!cropping && !state.recipe.isIdentity) {
            // Holding the picture does the same; this is for whoever cannot hold (a screen reader, a shaky hand) or has not found that yet.
            FilterChip(
                selected = comparing,
                onClick = { comparing = !comparing },
                label = { Text(stringResource(R.string.edit_original)) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }
    }
}

/** The picture, with the crop rectangle over it while the crop tool is open. */
@Composable
private fun PreviewPicture(shown: Bitmap, state: EditUiState, viewModel: EditViewModel, cropping: Boolean) {
    Image(shown.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    if (!cropping) return
    val aspect = shown.width.toFloat() / shown.height
    CropOverlay(aspect, state.recipe.geometry.crop, CropTool.ratioOf(state.cropShape, aspect)) { viewModel.geometry { g -> g.copy(crop = it) } }
}

/**
 * The crop rectangle over the picture: the outside is dimmed, the inside has a rule-of-thirds grid, and the corners, edges and middle can be
 * dragged. The arithmetic is in [CropTool]; this only turns touches on the screen into fractions of the picture.
 */
@Composable
private fun CropOverlay(imageAspect: Float, crop: Crop, ratio: Float?, onChange: (Crop) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentCrop by rememberUpdatedState(crop)
    val currentRatio by rememberUpdatedState(ratio)
    val shown = imageRect(size, imageAspect)
    var handle by remember { mutableStateOf<CropHandle?>(null) }
    val description = stringResource(R.string.crop_area, percent(crop.left), percent(crop.right), percent(crop.top), percent(crop.bottom))
    val actions = cropActions(crop, ratio, imageAspect, onChange)
    Canvas(
        Modifier.fillMaxSize().onSizeChanged { size = it }.semantics {
            contentDescription = description
            customActions = actions
        }.pointerInput(size, imageAspect) {
            detectDragGestures(
                onDragStart = { at ->
                    val rect = imageRect(size, imageAspect)
                    handle = CropTool.handleAt(currentCrop, (at.x - rect.left) / rect.width, (at.y - rect.top) / rect.height, TOUCH_SLOP * density / rect.width)
                },
                onDragEnd = { handle = null },
                onDragCancel = { handle = null },
                onDrag = { change, delta ->
                    val active = handle ?: return@detectDragGestures
                    change.consume()
                    val rect = imageRect(size, imageAspect)
                    onChange(CropTool.drag(currentCrop, active, delta.x / rect.width, delta.y / rect.height, currentRatio, imageAspect))
                },
            )
        },
    ) { drawCrop(shown, crop) }
}

private const val TOUCH_SLOP = 28f

/** How far one accessibility action moves or resizes the crop, as a fraction of the picture. */
private const val ACCESSIBILITY_STEP = 0.05f

private fun percent(fraction: Float) = (fraction * 100).roundToInt()

/**
 * What a finger does by dragging, as actions a screen reader can offer, since dragging is not possible with one: make the crop smaller or larger
 * (all four sides at once) and move it. Every action goes through the same [CropTool] rules as a drag.
 */
@Composable
private fun cropActions(crop: Crop, ratio: Float?, aspect: Float, onChange: (Crop) -> Unit): List<CustomAccessibilityAction> {
    val smaller = stringResource(R.string.crop_action_smaller)
    val larger = stringResource(R.string.crop_action_larger)
    val left = stringResource(R.string.crop_action_left)
    val right = stringResource(R.string.crop_action_right)
    val up = stringResource(R.string.crop_action_up)
    val down = stringResource(R.string.crop_action_down)
    fun act(label: String, change: (Crop) -> Crop) = CustomAccessibilityAction(label) {
        onChange(change(crop))
        true
    }
    fun drag(from: Crop, handle: CropHandle, dx: Float, dy: Float) = CropTool.drag(from, handle, dx, dy, ratio, aspect)
    val step = ACCESSIBILITY_STEP
    return listOf(
        act(smaller) { drag(drag(it, CropHandle.TOP_LEFT, step, step), CropHandle.BOTTOM_RIGHT, -step, -step) },
        act(larger) { drag(drag(it, CropHandle.TOP_LEFT, -step, -step), CropHandle.BOTTOM_RIGHT, step, step) },
        act(left) { drag(it, CropHandle.MOVE, -step, 0f) },
        act(right) { drag(it, CropHandle.MOVE, step, 0f) },
        act(up) { drag(it, CropHandle.MOVE, 0f, -step) },
        act(down) { drag(it, CropHandle.MOVE, 0f, step) },
    )
}

/** Where the picture is drawn inside a container of [size] (fitted and centred), in pixels. */
private fun imageRect(size: IntSize, aspect: Float): Rect {
    if (size.width == 0 || size.height == 0 || aspect <= 0f) return Rect(Offset.Zero, Size(1f, 1f))
    val containerAspect = size.width.toFloat() / size.height
    val (w, h) = if (aspect > containerAspect) size.width.toFloat() to size.width / aspect else size.height * aspect to size.height.toFloat()
    return Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrop(image: Rect, crop: Crop) {
    val rect = Rect(image.left + crop.left * image.width, image.top + crop.top * image.height, image.left + crop.right * image.width, image.top + crop.bottom * image.height)
    val dim = Color.Black.copy(alpha = 0.55f)
    drawRect(dim, Offset(image.left, image.top), Size(image.width, rect.top - image.top))
    drawRect(dim, Offset(image.left, rect.bottom), Size(image.width, image.bottom - rect.bottom))
    drawRect(dim, Offset(image.left, rect.top), Size(rect.left - image.left, rect.height))
    drawRect(dim, Offset(rect.right, rect.top), Size(image.right - rect.right, rect.height))
    drawRect(Color.White, rect.topLeft, rect.size, style = Stroke(width = 2.dp.toPx()))
    val grid = Color.White.copy(alpha = 0.5f)
    for (i in 1..2) {
        drawLine(grid, Offset(rect.left + rect.width * i / 3, rect.top), Offset(rect.left + rect.width * i / 3, rect.bottom), strokeWidth = 1.dp.toPx())
        drawLine(grid, Offset(rect.left, rect.top + rect.height * i / 3), Offset(rect.right, rect.top + rect.height * i / 3), strokeWidth = 1.dp.toPx())
    }
    val corner = 18.dp.toPx()
    for ((x, y, dx, dy) in listOf(Corner(rect.left, rect.top, 1f, 1f), Corner(rect.right, rect.top, -1f, 1f), Corner(rect.left, rect.bottom, 1f, -1f), Corner(rect.right, rect.bottom, -1f, -1f))) {
        drawLine(Color.White, Offset(x, y), Offset(x + dx * corner, y), strokeWidth = 4.dp.toPx())
        drawLine(Color.White, Offset(x, y), Offset(x, y + dy * corner), strokeWidth = 4.dp.toPx())
    }
}

private data class Corner(val x: Float, val y: Float, val dx: Float, val dy: Float)
