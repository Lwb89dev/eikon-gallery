package app.eikon.gallery.feature.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import app.eikon.gallery.data.ocr.OcrWord
import kotlin.math.roundToInt

/** A line's natural rendered height, at the font size this overlay gives it, is somewhat taller than the font size itself (ascent and descent); shrinking the target box by this much keeps the invisible glyphs roughly inside the line's own box rather than spilling into the next. An approximation: there is no single font size that makes an arbitrary rendered line exactly match a box written in a photo in some other, unknown font. */
private const val LINE_FONT_HEIGHT_RATIO = 0.8f

/** One line of recognized text, its words joined by spaces, boxed by the union of their boxes (fractions of the picture, like [OcrWord]). */
internal data class OcrLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * The words grouped into their lines, in reading order. One selectable per line, not per word, because [SelectionContainer] puts a line
 * break between every two selectables when it copies: per word, a copied sentence would come out one word per line.
 */
internal fun ocrLines(words: List<OcrWord>): List<OcrLine> =
    words.groupBy { it.line }.toSortedMap().values.map { line ->
        OcrLine(
            text = line.joinToString(" ") { it.text },
            left = line.minOf { it.left },
            top = line.minOf { it.top },
            right = line.maxOf { it.right },
            bottom = line.maxOf { it.bottom },
        )
    }

/**
 * The text OCR found in [mediaId], invisible but selectable exactly where it is written on the photo: long-press and drag to select,
 * the system's own toolbar to copy. Only while zoomed in (tiny unzoomed text is not worth trying to select, and at scale 1 a single
 * finger belongs to the pager and to drag-to-close) and only once there is text to show (nothing for a photo that has not been
 * analysed, or has none).
 *
 * Placed as a sibling of the picture itself inside [ZoomableBox]'s own pinch/pan transform, so it pans and scales together with the
 * photo for free: the lines below are positioned within the picture's fitted (scale 1) rectangle, never touching [ZoomState.scale] or
 * [ZoomState.offset] directly. A single finger moving over the text still pans the photo: the selection only claims the finger once a
 * long press has fired (Compose's own `awaitLongPressOrCancellation` consumes nothing while it waits).
 */
@Composable
fun OcrTextLayer(mediaId: Long, state: ZoomState, wordsOf: suspend (Long) -> List<OcrWord>) {
    if (!state.isZoomed) return
    val currentWordsOf by rememberUpdatedState(wordsOf)
    val words by produceState(initialValue = emptyList<OcrWord>(), mediaId) { value = currentWordsOf(mediaId) }
    val lines = remember(words) { ocrLines(words) }
    if (lines.isEmpty()) return
    val fitted = state.fittedRect()
    if (fitted.width <= 0f || fitted.height <= 0f) return
    SelectionContainer {
        Box(Modifier.fillMaxSize()) {
            lines.forEach { line -> OcrLineText(line, fitted) }
        }
    }
}

/**
 * One line, placed over its own spot in [fitted] and stretched horizontally to the width OCR measured for it: the font here is never
 * the photo's own, so only the box, not the glyph shapes, can match it. The text itself is transparent; [SelectionContainer] still
 * paints its usual highlight behind what is selected and hands the real recognized string to the copy menu, which is the only thing
 * that has to be exactly right.
 */
@Composable
private fun OcrLineText(line: OcrLine, fitted: Rect) {
    val density = LocalDensity.current
    val targetWidthPx = (line.right - line.left) * fitted.width
    val targetHeightPx = (line.bottom - line.top) * fitted.height
    if (targetWidthPx <= 0f || targetHeightPx <= 0f) return
    val style = remember(targetHeightPx, density) { TextStyle(fontSize = with(density) { (targetHeightPx * LINE_FONT_HEIGHT_RATIO).toSp() }) }
    val measurer = rememberTextMeasurer()
    val naturalWidthPx = remember(line.text, style, measurer) { measurer.measure(line.text, style).size.width.toFloat() }
    val stretch = if (naturalWidthPx > 0f) targetWidthPx / naturalWidthPx else 1f
    val position = IntOffset((fitted.left + line.left * fitted.width).roundToInt(), (fitted.top + line.top * fitted.height).roundToInt())
    Text(
        text = line.text,
        style = style,
        color = Color.Transparent,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        // Placed by layout (offset), so the selection's own ordering and hit-testing see the line where it really is; only the
        // horizontal stretch, which layout cannot express, is left to the graphics layer.
        modifier = Modifier
            .offset { position }
            .graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                scaleX = stretch
            }
            .fixedWidth(density, naturalWidthPx)
            .fixedHeight(density, targetHeightPx),
    )
}

private fun Modifier.fixedWidth(density: Density, px: Float): Modifier = width(with(density) { px.toDp() })

private fun Modifier.fixedHeight(density: Density, px: Float): Modifier = height(with(density) { px.toDp() })
