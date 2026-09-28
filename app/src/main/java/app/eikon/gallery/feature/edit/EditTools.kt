package app.eikon.gallery.feature.edit

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.domain.edit.Adjustments
import app.eikon.gallery.domain.edit.CropShape
import app.eikon.gallery.domain.edit.EditFilter
import app.eikon.gallery.domain.edit.EditRecipe
import app.eikon.gallery.domain.edit.Geometry
import java.util.Locale

/**
 * What every tool needs to do, so [EditTools] can be shared by the photo editor and the video editor instead of depending on
 * [EditViewModel] itself. [autoEnhance] is null where there is nothing to suggest from (a video: nothing here looks at its frames).
 */
interface EditToolsActions {
    fun adjust(change: (Adjustments) -> Adjustments)
    fun setFilter(filter: EditFilter)
    fun setFilterAmount(amount: Float)
    fun geometry(change: (Geometry) -> Geometry)
    fun rotate()
    fun flip()
    fun setCropShape(shape: CropShape)
    val autoEnhance: (() -> Unit)?
}

/**
 * The panel of the chosen tool and the row of tools under it. [isVideo] hides straighten and perspective, which only a still picture's
 * geometry supports today: see [app.eikon.gallery.data.edit.VideoEditEffects].
 */
@Composable
fun EditTools(
    recipe: EditRecipe,
    tool: EditTool,
    cropShape: CropShape,
    filterThumbnails: Map<EditFilter, Bitmap>,
    actions: EditToolsActions,
    onSelectTool: (EditTool) -> Unit,
    isVideo: Boolean = false,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(max = PANEL_MAX_HEIGHT).padding(horizontal = 16.dp)) {
            ToolPanel(recipe, tool, cropShape, filterThumbnails, actions, isVideo)
        }
        ToolRow(tool, actions, onSelectTool, isVideo)
    }
}

/** The controls of the tool that is open. */
@Composable
private fun ToolPanel(recipe: EditRecipe, tool: EditTool, cropShape: CropShape, filterThumbnails: Map<EditFilter, Bitmap>, actions: EditToolsActions, isVideo: Boolean) {
    when (tool) {
        EditTool.LIGHT -> LightPanel(recipe.adjustments, actions)
        EditTool.COLOR -> ColorPanel(recipe.adjustments, actions)
        // Sharpening and the vignette are not applied to video yet (see VideoEditEffects); the tool itself is hidden there, so this is unreachable for a video.
        EditTool.DETAIL -> DetailPanel(recipe.adjustments, actions)
        EditTool.FILTERS -> FilterPanel(recipe, filterThumbnails, actions)
        EditTool.CROP -> CropPanel(recipe, cropShape, actions, isVideo)
    }
}

private val PANEL_MAX_HEIGHT = 230.dp

// --- The row of tools -----------------------------------------------------------------------------

/**
 * The tools. They spread over the width when they fit, and scroll sideways when they do not (a narrow phone, or large text). Detail
 * (sharpening, vignette) is left out for a video: nothing draws it yet, and a tool that visibly changes nothing is worse than no tool.
 */
@Composable
private fun ToolRow(tool: EditTool, actions: EditToolsActions, onSelectTool: (EditTool) -> Unit, isVideo: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).widthIn(min = maxWidth).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            actions.autoEnhance?.let { ToolButton(R.drawable.ic_auto_fix, R.string.tool_auto, selected = false, role = Role.Button, onClick = it) }
            ToolButton(R.drawable.ic_tune, R.string.tool_light, tool == EditTool.LIGHT) { onSelectTool(EditTool.LIGHT) }
            ToolButton(R.drawable.ic_palette, R.string.tool_color, tool == EditTool.COLOR) { onSelectTool(EditTool.COLOR) }
            if (!isVideo) ToolButton(R.drawable.ic_detail, R.string.tool_detail, tool == EditTool.DETAIL) { onSelectTool(EditTool.DETAIL) }
            ToolButton(R.drawable.ic_photo_filter, R.string.tool_filters, tool == EditTool.FILTERS) { onSelectTool(EditTool.FILTERS) }
            ToolButton(R.drawable.ic_crop, R.string.tool_crop, tool == EditTool.CROP) { onSelectTool(EditTool.CROP) }
        }
    }
}

@Composable
private fun ToolButton(@DrawableRes icon: Int, @StringRes label: Int, selected: Boolean, role: Role = Role.Tab, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .semantics { if (role == Role.Tab) this.selected = selected }
            .clickable(role = role, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint)
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

// --- Sliders ----------------------------------------------------------------------------------------

/** A labelled slider that shows its value and returns to [neutral] when the label is tapped. */
@Composable
private fun AdjustSlider(@StringRes label: Int, value: Float, range: ClosedFloatingPointRange<Float>, neutral: Float = 0f, onChange: (Float) -> Unit) {
    val name = stringResource(label)
    val valueText = String.format(Locale.US, "%+.2f", value)
    val resetLabel = stringResource(R.string.slider_reset)
    Column(Modifier.fillMaxWidth()) {
        // Sighted users tap the name to reset; for a screen reader the slider itself carries the name, the value and the reset, so this row is hidden from it.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).clickable { onChange(neutral) })
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = if (value == neutral) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics {
                contentDescription = name
                stateDescription = valueText
                customActions = listOf(CustomAccessibilityAction(resetLabel) { onChange(neutral); true })
            },
        )
    }
}

@Composable
private fun LightPanel(a: Adjustments, actions: EditToolsActions) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        AdjustSlider(R.string.adj_exposure, a.exposure, -Adjustments.EXPOSURE_RANGE..Adjustments.EXPOSURE_RANGE) { v -> actions.adjust { it.copy(exposure = v) } }
        AdjustSlider(R.string.adj_brightness, a.brightness, UNIT) { v -> actions.adjust { it.copy(brightness = v) } }
        AdjustSlider(R.string.adj_contrast, a.contrast, UNIT) { v -> actions.adjust { it.copy(contrast = v) } }
        AdjustSlider(R.string.adj_highlights, a.highlights, UNIT) { v -> actions.adjust { it.copy(highlights = v) } }
        AdjustSlider(R.string.adj_shadows, a.shadows, UNIT) { v -> actions.adjust { it.copy(shadows = v) } }
        AdjustSlider(R.string.adj_black_point, a.blackPoint, UNIT) { v -> actions.adjust { it.copy(blackPoint = v) } }
    }
}

@Composable
private fun ColorPanel(a: Adjustments, actions: EditToolsActions) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        AdjustSlider(R.string.adj_saturation, a.saturation, UNIT) { v -> actions.adjust { it.copy(saturation = v) } }
        AdjustSlider(R.string.adj_vibrance, a.vibrance, UNIT) { v -> actions.adjust { it.copy(vibrance = v) } }
        AdjustSlider(R.string.adj_temperature, a.temperature, UNIT) { v -> actions.adjust { it.copy(temperature = v) } }
        AdjustSlider(R.string.adj_tint, a.tint, UNIT) { v -> actions.adjust { it.copy(tint = v) } }
    }
}

@Composable
private fun DetailPanel(a: Adjustments, actions: EditToolsActions) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        AdjustSlider(R.string.adj_sharpness, a.sharpness, 0f..1f) { v -> actions.adjust { it.copy(sharpness = v) } }
        AdjustSlider(R.string.adj_vignette, a.vignette, UNIT) { v -> actions.adjust { it.copy(vignette = v) } }
    }
}

private val UNIT = -1f..1f

// --- Filters ------------------------------------------------------------------------------------------

@Composable
private fun FilterPanel(recipe: EditRecipe, filterThumbnails: Map<EditFilter, Bitmap>, actions: EditToolsActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(EditFilter.entries.toList(), key = { it.name }) { filter -> FilterItem(filter, recipe, filterThumbnails, actions) }
        }
        if (recipe.filter != EditFilter.NONE) {
            AdjustSlider(R.string.filter_amount, recipe.filterAmount, 0f..1f, neutral = 1f, onChange = actions::setFilterAmount)
        }
    }
}

@Composable
private fun FilterItem(filter: EditFilter, recipe: EditRecipe, filterThumbnails: Map<EditFilter, Bitmap>, actions: EditToolsActions) {
    val selected = recipe.filter == filter
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest
    Column(Modifier.selectable(selected = selected, role = Role.RadioButton) { actions.setFilter(filter) }, horizontalAlignment = Alignment.CenterHorizontally) {
        val thumbnail = filterThumbnails[filter]
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, outline, RoundedCornerShape(8.dp))) {
            if (thumbnail != null) Image(thumbnail.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
        }
        Text(stringResource(filterName(filter)), style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@StringRes
private fun filterName(filter: EditFilter): Int = when (filter) {
    EditFilter.NONE -> R.string.filter_name_none
    EditFilter.VIVID -> R.string.filter_name_vivid
    EditFilter.WARM -> R.string.filter_name_warm
    EditFilter.COOL -> R.string.filter_name_cool
    EditFilter.MONO -> R.string.filter_name_mono
    EditFilter.NOIR -> R.string.filter_name_noir
    EditFilter.FADE -> R.string.filter_name_fade
    EditFilter.CHROME -> R.string.filter_name_chrome
    EditFilter.SEPIA -> R.string.filter_name_sepia
}

// --- Crop and geometry --------------------------------------------------------------------------------

@Composable
private fun CropPanel(recipe: EditRecipe, cropShape: CropShape, actions: EditToolsActions, isVideo: Boolean) {
    val g = recipe.geometry
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions::rotate) { Icon(painterResource(R.drawable.ic_rotate_right), stringResource(R.string.crop_rotate)) }
            IconButton(onClick = actions::flip) { Icon(painterResource(R.drawable.ic_flip), stringResource(R.string.crop_flip)) }
            ShapeChips(cropShape, actions::setCropShape)
        }
        AdjustSlider(R.string.geo_straighten, g.straightenDegrees, -Geometry.MAX_STRAIGHTEN..Geometry.MAX_STRAIGHTEN) { v -> actions.geometry { it.copy(straightenDegrees = v) } }
        // Perspective needs a projective warp that video does not have yet (VideoEditEffects covers only crop, quarter turns, flip and straighten).
        if (!isVideo) {
            AdjustSlider(R.string.geo_perspective_vertical, g.perspectiveVertical, UNIT) { v -> actions.geometry { it.copy(perspectiveVertical = v) } }
            AdjustSlider(R.string.geo_perspective_horizontal, g.perspectiveHorizontal, UNIT) { v -> actions.geometry { it.copy(perspectiveHorizontal = v) } }
        }
    }
}

/** The shapes the crop can be locked to, one chip each. */
@Composable
private fun ShapeChips(selected: CropShape, onSelect: (CropShape) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(CropShape.entries.toList(), key = { it.name }) { shape ->
            FilterChip(selected = selected == shape, onClick = { onSelect(shape) }, label = { Text(stringResource(shapeName(shape)), textAlign = TextAlign.Center) })
        }
    }
}

@StringRes
private fun shapeName(shape: CropShape): Int = when (shape) {
    CropShape.FREE -> R.string.shape_free
    CropShape.ORIGINAL -> R.string.shape_original
    CropShape.SQUARE -> R.string.shape_square
    CropShape.FOUR_THREE -> R.string.shape_4_3
    CropShape.THREE_TWO -> R.string.shape_3_2
    CropShape.SIXTEEN_NINE -> R.string.shape_16_9
}
