package app.eikon.gallery.domain

/**
 * The EXIF tags eikon may rewrite (a photo's date and its location), as they read in the file, so what a photo said before a change can be kept and put back. A snapshot is
 * just the tags that were present, by name, exactly as the file spelled them: nothing is interpreted, so nothing is lost by putting it back.
 */
object ExifFields {
    /** Names as in `androidx.exifinterface.media.ExifInterface` (`TAG_*`). */
    val DATE_TAGS = listOf("DateTimeOriginal", "DateTimeDigitized", "OffsetTimeOriginal", "OffsetTimeDigitized")

    val GPS_TAGS = listOf(
        "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp", "GPSProcessingMethod",
    )

    /** The tags kept for a field ([app.eikon.gallery.data.db.MetadataOriginalEntity.FIELD_DATE], `FIELD_LOCATION`). */
    fun tagsOf(field: String): List<String> = when (field) {
        "DATE" -> DATE_TAGS
        "LOCATION" -> GPS_TAGS
        else -> emptyList()
    }
}

/** A snapshot written as text (for the database) and read back. One `tag=value` per line; a backslash, a newline or a carriage return inside a value is escaped. */
object ExifSnapshot {
    fun encode(tags: Map<String, String>): String =
        tags.entries.sortedBy { it.key }.joinToString("\n") { (name, value) -> "$name=${escape(value)}" }

    /** The tags in [text]; a line that is not `tag=value` is skipped rather than guessed at. */
    fun decode(text: String): Map<String, String> {
        val tags = LinkedHashMap<String, String>()
        for (line in text.split('\n')) {
            val at = line.indexOf('=')
            if (at > 0) tags[line.substring(0, at)] = unescape(line.substring(at + 1))
        }
        return tags
    }

    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) {
                out.append(c)
                i++
                continue
            }
            out.append(
                when (value[i + 1]) {
                    'n' -> '\n'
                    'r' -> '\r'
                    else -> value[i + 1]
                },
            )
            i += 2
        }
        return out.toString()
    }
}
