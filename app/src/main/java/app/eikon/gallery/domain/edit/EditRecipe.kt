package app.eikon.gallery.domain.edit

/**
 * The tone and color adjustments of an edit. Every value is a plain number with a neutral default of 0, so a recipe says
 * what was changed and by how much, never a picture: the original file is never touched. Ranges are noted per field;
 * [clamped] forces a value into its range.
 */
data class Adjustments(
    /** Exposure in stops (EV), -2 to 2: each stop doubles or halves the light. */
    val exposure: Float = 0f,
    /** Lifts or lowers the middle tones without moving pure black or white, -1 to 1. */
    val brightness: Float = 0f,
    /** Separates light from dark, -1 to 1. */
    val contrast: Float = 0f,
    /** Darkens (negative) or brightens (positive) only the lightest areas, -1 to 1. */
    val highlights: Float = 0f,
    /** Brightens (positive) or darkens (negative) only the darkest areas, -1 to 1. */
    val shadows: Float = 0f,
    /** Moves the level that counts as black: positive makes blacks deeper, -1 to 1. */
    val blackPoint: Float = 0f,
    /** Overall color strength, -1 (gray) to 1 (twice as colorful). */
    val saturation: Float = 0f,
    /** Color strength for the dull colors first, sparing already strong ones and skin, -1 to 1. */
    val vibrance: Float = 0f,
    /** Cooler (negative, bluer) to warmer (positive, more orange), -1 to 1. */
    val temperature: Float = 0f,
    /** Greener (negative) to more magenta (positive), -1 to 1. */
    val tint: Float = 0f,
    /** Edge crispness, 0 to 1. */
    val sharpness: Float = 0f,
    /** Darkens (positive) or lightens (negative) the corners, -1 to 1. */
    val vignette: Float = 0f,
) {
    val isNeutral: Boolean get() = this == NONE

    fun clamped() = Adjustments(
        exposure.coerceIn(-EXPOSURE_RANGE, EXPOSURE_RANGE), brightness.unit(), contrast.unit(), highlights.unit(), shadows.unit(),
        blackPoint.unit(), saturation.unit(), vibrance.unit(), temperature.unit(), tint.unit(), sharpness.coerceIn(0f, 1f), vignette.unit(),
    )

    private fun Float.unit() = coerceIn(-1f, 1f)

    companion object {
        val NONE = Adjustments()
        const val EXPOSURE_RANGE = 2f
    }
}

/** The part of the picture kept, as fractions of the (rotated and straightened) picture: 0,0 is the top left, 1,1 the bottom right. */
data class Crop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val isFull: Boolean get() = this == FULL

    /** Inside 0..1 and at least [MIN_SIZE] wide and tall. */
    fun clamped(): Crop {
        val l = left.coerceIn(0f, 1f - MIN_SIZE)
        val t = top.coerceIn(0f, 1f - MIN_SIZE)
        return Crop(l, t, right.coerceIn(l + MIN_SIZE, 1f), bottom.coerceIn(t + MIN_SIZE, 1f))
    }

    companion object {
        val FULL = Crop()
        const val MIN_SIZE = 0.05f
    }
}

/**
 * How the picture is turned and cut, applied in this order: flip, quarter turns, then straighten and perspective (with the
 * picture enlarged just enough that no empty corner shows), then the crop. All of it is parameters; the pixels are only
 * resampled once, when a picture is drawn.
 */
data class Geometry(
    /** Clockwise quarter turns, 0 to 3. */
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    /** Tilt to level a horizon, in degrees, -45 to 45. Positive turns the picture clockwise. */
    val straightenDegrees: Float = 0f,
    /** Corrects converging verticals (a building shot from below): -1 to 1. */
    val perspectiveVertical: Float = 0f,
    /** Corrects converging horizontals (a shot from the side): -1 to 1. */
    val perspectiveHorizontal: Float = 0f,
    val crop: Crop = Crop.FULL,
) {
    val isNeutral: Boolean get() = this == NONE

    fun clamped() = Geometry(
        Math.floorMod(quarterTurns, QUARTERS), flipHorizontal, flipVertical, straightenDegrees.coerceIn(-MAX_STRAIGHTEN, MAX_STRAIGHTEN),
        perspectiveVertical.coerceIn(-1f, 1f), perspectiveHorizontal.coerceIn(-1f, 1f), crop.clamped(),
    )

    companion object {
        val NONE = Geometry()
        const val QUARTERS = 4
        const val MAX_STRAIGHTEN = 45f
    }
}

/** Ready-made looks. Each is a small, fixed set of color changes; [EditRecipe.filterAmount] says how strongly it is applied. */
enum class EditFilter { NONE, VIVID, WARM, COOL, MONO, NOIR, FADE, CHROME, SEPIA }

/**
 * Everything done to one photo, without changing the photo. It is a value: two recipes that say the same thing are equal,
 * it is stored as a short piece of text ([EditRecipeCodec]), and it can be copied to other photos.
 */
data class EditRecipe(
    val adjustments: Adjustments = Adjustments.NONE,
    val geometry: Geometry = Geometry.NONE,
    val filter: EditFilter = EditFilter.NONE,
    /** 0 to 1. */
    val filterAmount: Float = 1f,
) {
    /** True when drawing the recipe would change nothing. */
    val isIdentity: Boolean get() = adjustments.isNeutral && geometry.isNeutral && (filter == EditFilter.NONE || filterAmount <= 0f)

    fun clamped() = EditRecipe(adjustments.clamped(), geometry.clamped(), filter, filterAmount.coerceIn(0f, 1f))

    /**
     * What "Paste edits" applies: the look (adjustments and filter) but not the crop, turns or straightening, which belong to
     * one particular picture and would cut or tilt another one.
     */
    fun pasteable(): EditRecipe = copy(geometry = Geometry.NONE)

    companion object {
        val NONE = EditRecipe()
    }
}

/**
 * The recipe as text, so it can live in a database column and in a clipboard without any library: a version line, then `key=value`
 * for everything that is not neutral. Unknown keys are ignored, so a later version that adds a tool can still be read (the tool is
 * simply not applied); a text that is not a recipe reads as no recipe.
 */
object EditRecipeCodec {
    private const val HEADER = "eikon-edit"
    private const val VERSION = 1

    fun encode(recipe: EditRecipe): String {
        val r = recipe.clamped()
        val lines = mutableListOf("$HEADER $VERSION")
        val a = r.adjustments
        fun put(key: String, value: Float) {
            if (value != 0f) lines += "$key=$value"
        }
        put("exposure", a.exposure); put("brightness", a.brightness); put("contrast", a.contrast); put("highlights", a.highlights)
        put("shadows", a.shadows); put("blackPoint", a.blackPoint); put("saturation", a.saturation); put("vibrance", a.vibrance)
        put("temperature", a.temperature); put("tint", a.tint); put("sharpness", a.sharpness); put("vignette", a.vignette)
        val g = r.geometry
        if (g.quarterTurns != 0) lines += "turns=${g.quarterTurns}"
        if (g.flipHorizontal) lines += "flipH=1"
        if (g.flipVertical) lines += "flipV=1"
        put("straighten", g.straightenDegrees); put("perspectiveV", g.perspectiveVertical); put("perspectiveH", g.perspectiveHorizontal)
        if (!g.crop.isFull) lines += "crop=${g.crop.left},${g.crop.top},${g.crop.right},${g.crop.bottom}"
        if (r.filter != EditFilter.NONE) {
            lines += "filter=${r.filter.name.lowercase()}"
            if (r.filterAmount != 1f) lines += "filterAmount=${r.filterAmount}"
        }
        return lines.joinToString("\n")
    }

    /** The recipe in [text], or null if it is not one. */
    fun decode(text: String): EditRecipe? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val header = lines.firstOrNull()?.split(' ') ?: return null
        if (header.size != 2 || header[0] != HEADER || header[1].toIntOrNull() == null) return null
        val values = lines.drop(1).mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        fun number(key: String) = values[key]?.toFloatOrNull() ?: 0f
        val adjustments = Adjustments(
            number("exposure"), number("brightness"), number("contrast"), number("highlights"), number("shadows"), number("blackPoint"),
            number("saturation"), number("vibrance"), number("temperature"), number("tint"), number("sharpness"), number("vignette"),
        )
        val geometry = Geometry(
            values["turns"]?.toIntOrNull() ?: 0, values["flipH"] == "1", values["flipV"] == "1", number("straighten"),
            number("perspectiveV"), number("perspectiveH"), values["crop"]?.let(::cropOf) ?: Crop.FULL,
        )
        val filter = EditFilter.entries.firstOrNull { it.name.equals(values["filter"], ignoreCase = true) } ?: EditFilter.NONE
        return EditRecipe(adjustments, geometry, filter, values["filterAmount"]?.toFloatOrNull() ?: 1f).clamped()
    }

    private fun cropOf(text: String): Crop? {
        val v = text.split(',').map { it.toFloatOrNull() ?: return null }
        return if (v.size == 4) Crop(v[0], v[1], v[2], v[3]) else null
    }
}
