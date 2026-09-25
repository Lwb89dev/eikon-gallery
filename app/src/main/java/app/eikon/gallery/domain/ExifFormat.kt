package app.eikon.gallery.domain

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.roundToInt

data class GeoPoint(val latitude: Double, val longitude: Double)

/** Capture time as written by the camera: local wall-clock time, plus the UTC offset when recorded. */
data class CaptureTime(val local: LocalDateTime, val offset: ZoneOffset?)

/**
 * Pure formatting/parsing helpers for the info panel. Technical values (f-number, shutter, ISO...)
 * are deliberately locale-neutral: photographers expect "f/1.8" and "1/250 s" everywhere.
 */
object ExifFormat {
    private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
    private val ISO6709 = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)")

    fun aperture(fNumber: Double?): String? =
        fNumber?.takeIf { it > 0 }?.let { "f/${trimmed(it, 1)}" }

    fun shutter(seconds: Double?): String? {
        if (seconds == null || seconds <= 0) return null
        if (seconds >= 1) return "${trimmed(seconds, 1)} s"
        return "1/${(1 / seconds).roundToInt()} s"
    }

    fun focalLength(millimetres: Double?): String? =
        millimetres?.takeIf { it > 0 }?.let { "${trimmed(it, 1)} mm" }

    fun exposureBias(ev: Double?): String? {
        if (ev == null) return null
        val sign = if (ev > 0) "+" else ""
        return "$sign${trimmed(ev, 2)} EV"
    }

    fun iso(value: Int?): String? = value?.takeIf { it > 0 }?.let { "ISO $it" }

    fun megapixels(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val mp = width.toLong() * height / 1_000_000.0
        return "${trimmed(mp, 1)} MP"
    }

    fun resolution(width: Int, height: Int): String? =
        if (width > 0 && height > 0) "$width × $height" else null

    /** "1:05" or "1:02:03". */
    fun duration(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    fun bitrate(bitsPerSecond: Long?): String? {
        if (bitsPerSecond == null || bitsPerSecond <= 0) return null
        return if (bitsPerSecond >= 1_000_000) {
            "${trimmed(bitsPerSecond / 1_000_000.0, 1)} Mbps"
        } else {
            "${(bitsPerSecond / 1000.0).roundToInt()} kbps"
        }
    }

    fun frameRate(fps: Double?): String? =
        fps?.takeIf { it > 0 }?.let { "${trimmed(it, 2)} fps" }

    fun videoCodec(mimeType: String?): String? = when (mimeType?.lowercase()) {
        null -> null
        "video/avc" -> "H.264 (AVC)"
        "video/hevc" -> "H.265 (HEVC)"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4 Visual"
        "video/3gpp" -> "H.263"
        "video/dolby-vision" -> "Dolby Vision"
        else -> mimeType.substringAfter('/')
    }

    fun audioCodec(mimeType: String?): String? = when (mimeType?.lowercase()) {
        null -> null
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/ac3" -> "AC-3"
        "audio/eac3" -> "E-AC-3"
        "audio/flac" -> "FLAC"
        "audio/3gpp", "audio/amr-wb", "audio/amr" -> "AMR"
        else -> mimeType.substringAfter('/')
    }

    /** Parses EXIF `yyyy:MM:dd HH:mm:ss` plus an optional `±HH:MM` offset tag. */
    fun captureTime(dateTime: String?, offset: String?): CaptureTime? {
        if (dateTime.isNullOrBlank()) return null
        val local = try {
            LocalDateTime.parse(dateTime.trim(), EXIF_DATE)
        } catch (_: DateTimeParseException) {
            return null
        }
        return CaptureTime(local, parseOffset(offset))
    }

    /** ISO 6709 as stored in MP4/MOV `location` metadata, e.g. "+37.4220-122.0841+012.000/". */
    fun parseIso6709(value: String?): GeoPoint? {
        val match = value?.let { ISO6709.find(it) } ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        return geoPoint(latitude, longitude)
    }

    /**
     * Null for out-of-range values and for exactly 0,0: cameras without a GPS fix commonly write that,
     * and a photo taken in the Gulf of Guinea is far less likely than a missing location.
     */
    fun geoPoint(latitude: Double, longitude: Double): GeoPoint? {
        val inRange = latitude in -90.0..90.0 && longitude in -180.0..180.0
        val noFix = latitude == 0.0 && longitude == 0.0
        return if (inRange && !noFix) GeoPoint(latitude, longitude) else null
    }

    /** "Google Pixel 8" from make "Google" + model "Pixel 8"; avoids "samsung samsung SM-S918B". */
    fun deviceName(make: String?, model: String?): String? {
        val cleanMake = make?.trim().orEmpty()
        val cleanModel = model?.trim().orEmpty()
        return when {
            cleanModel.isEmpty() -> cleanMake.ifEmpty { null }
            cleanMake.isEmpty() || cleanModel.startsWith(cleanMake, ignoreCase = true) -> cleanModel
            else -> "$cleanMake $cleanModel"
        }
    }

    /** True for the EXIF orientations that turn the image sideways (5 to 8), swapping width and height. */
    fun orientationSwapsAxes(exifOrientation: Int): Boolean = exifOrientation in 5..8

    /** Rotation needed to display the image upright, with "↔" appended when it is also mirrored. */
    fun orientation(exifOrientation: Int): String? {
        val (degrees, mirrored) = when (exifOrientation) {
            1 -> 0 to false
            2 -> 0 to true
            3 -> 180 to false
            4 -> 180 to true
            5 -> 90 to true
            6 -> 90 to false
            7 -> 270 to true
            8 -> 270 to false
            else -> return null
        }
        return if (mirrored) "$degrees° ↔" else "$degrees°"
    }

    private fun parseOffset(offset: String?): ZoneOffset? {
        if (offset.isNullOrBlank()) return null
        return try {
            ZoneOffset.of(offset.trim())
        } catch (_: DateTimeException) {
            null
        }
    }

    /** At most [decimals] decimal places, without trailing zeros ("1.80" -> "1.8", "2.0" -> "2"). */
    private fun trimmed(value: Double, decimals: Int): String {
        val text = String.format(Locale.ROOT, "%.${decimals}f", value)
        return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
    }
}
