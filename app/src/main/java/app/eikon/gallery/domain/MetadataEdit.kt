package app.eikon.gallery.domain

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Which files eikon can write the date and the location into (the formats Android's ExifInterface can write); everything else is shown but not editable. */
object EditableFormats {
    private val WRITABLE = setOf("image/jpeg", "image/png", "image/webp")

    fun canWrite(mimeType: String): Boolean = mimeType.lowercase(Locale.ROOT) in WRITABLE
}

/** How a date is written into EXIF, which has its own format and its own way of saying the time zone. */
object ExifText {
    private val DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)

    /** `2025:08:12 14:30:00`, the wall-clock time exactly as it will read back. */
    fun dateTime(local: LocalDateTime): String = local.format(DATE)

    /** `+02:00`, the form of the EXIF offset tags. */
    fun offset(offset: ZoneOffset): String = offset.id.let { if (it == "Z") "+00:00" else it }
}

/**
 * Reads a position typed or pasted by a person: two numbers in decimal degrees, latitude first, separated by a comma, a semicolon or spaces, with a decimal point or a
 * decimal comma, each optionally with the hemisphere (`N`, `S`, `E`, `W`) before or after it. Anything else, or a position that is not on Earth, is not understood
 * (null): a wrong reading would put a photo in the wrong place in the file itself.
 */
object CoordinateParser {
    private val WHITESPACE = Regex("\\s+")
    private val HEMISPHERE_FIRST = Regex("^\\s*([NSns]\\s*[+-]?\\d+(?:[.,]\\d+)?|[+-]?\\d+(?:[.,]\\d+)?\\s*[NSns])\\s*[;,]?\\s*(.+)$")

    fun parse(text: String): GeoPoint? {
        val parts = split(text.trim()) ?: return null
        val latitude = number(parts[0], 'N', 'S') ?: return null
        val longitude = number(parts[1], 'E', 'W') ?: return null
        return if (latitude in -90.0..90.0 && longitude in -180.0..180.0) GeoPoint(latitude, longitude) else null
    }

    /** Two pieces: split at a semicolon, else after a hemisphere letter, else at a comma, else at white space (a bare comma may be a decimal comma, so it comes late). */
    private fun split(text: String): List<String>? {
        if (text.isEmpty()) return null
        text.split(';').map { it.trim() }.takeIf { it.size == 2 }?.let { return it }
        HEMISPHERE_FIRST.matchEntire(text)?.let { return listOf(it.groupValues[1].trim(), it.groupValues[2].trim()) }
        text.split(',').map { it.trim() }.takeIf { it.size == 2 && it.none(String::isEmpty) }?.let { return it }
        return text.split(WHITESPACE).takeIf { it.size == 2 }
    }

    /** One number of degrees, with the letter of its hemisphere at either end if there is one. */
    private fun number(piece: String, positive: Char, negative: Char): Double? {
        val text = piece.trim()
        val ends = listOfNotNull(text.firstOrNull(), text.lastOrNull()).filter { it.isLetter() }.map { it.uppercaseChar() }
        val onlyHemisphereLetters = ends.all { it == positive || it == negative } && text.count { it.isLetter() } == ends.size
        if (!onlyHemisphereLetters || ends.size > 1) return null
        val value = text.filterNot { it.isLetter() }.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (ends.isEmpty()) return value
        if (value < 0) return null // "-41 S" contradicts itself
        return if (ends.single() == negative) -value else value
    }
}
