package app.eikon.gallery.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.eikon.gallery.domain.search.TimeRange

/** Photos per day on the user's calendar. */
class DayCountRow(val day: String, val count: Int)

/** A named person's photos in one year. */
class PersonYearRow(val personId: Long, val isFavorite: Boolean, val year: Int, val count: Int)

/** A photo that could be shown in a memory. Columns match the query below. */
class KeyCandidateRow(val id: Long, val takenAt: Long, val width: Int, val height: Int, val isFavorite: Boolean, val faces: Int)

/** SQL for memories, as constants and a builder so tests can run exactly this text on a real SQLite. */
object MemoryQueries {
    /** Photos and videos per local day, leaving out screenshots and screen recordings and anything hidden. */
    const val DAY_COUNTS = """
        SELECT strftime('%Y-%m-%d', takenAt / 1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS count
        FROM media
        WHERE isScreenshot = 0 AND isScreenRecording = 0 AND id NOT IN (SELECT mediaId FROM hidden_media)
        GROUP BY day
        """

    /** Named, not hidden people with the number of visible photos they are in, per year. */
    const val PERSON_YEARS = """
        SELECT f.personId AS personId, p.isFavorite AS isFavorite,
               CAST(strftime('%Y', m.takenAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS year, COUNT(DISTINCT m.id) AS count
        FROM face f JOIN person p ON p.id = f.personId JOIN media m ON m.id = f.mediaId
        WHERE f.ignored = 0 AND p.name IS NOT NULL AND p.isHidden = 0 AND m.isScreenshot = 0
          AND m.id NOT IN (SELECT mediaId FROM hidden_media)
        GROUP BY f.personId, year
        """

    /**
     * Photos (not videos, screenshots or hidden ones) taken in any of [periods] (`[start, end)` in epoch millis), oldest first;
     * only those with [person] if given; without anyone in [lessOf]; leaving out photos taken on [excludedDays] (`yyyy-mm-dd`).
     * Every value is a bound argument.
     */
    fun candidates(periods: List<TimeRange>, person: Long?, lessOf: Set<Long>, excludedDays: Set<String>): SimpleSQLiteQuery {
        require(periods.isNotEmpty()) { "a memory has at least one period" }
        val args = ArrayList<Any>()
        val conditions = mutableListOf(
            "m.isVideo = 0", "m.isScreenshot = 0", "m.isScreenRecording = 0", "m.id NOT IN (SELECT mediaId FROM hidden_media)",
            periods.joinToString(" OR ", "(", ")") { period ->
                args += period.startMillis
                args += period.endMillis
                "(m.takenAt >= ? AND m.takenAt < ?)"
            },
        )
        if (person != null) {
            args += person
            conditions += "m.id IN (SELECT mediaId FROM face WHERE personId = ? AND ignored = 0)"
        }
        if (lessOf.isNotEmpty()) {
            args.addAll(lessOf)
            conditions += "m.id NOT IN (SELECT mediaId FROM face WHERE personId IN (${lessOf.joinToString(",") { "?" }}) AND ignored = 0)"
        }
        if (excludedDays.isNotEmpty()) {
            args.addAll(excludedDays)
            conditions += "strftime('%Y-%m-%d', m.takenAt / 1000, 'unixepoch', 'localtime') NOT IN (${excludedDays.joinToString(",") { "?" }})"
        }
        val sql = "SELECT m.id AS id, m.takenAt AS takenAt, m.width AS width, m.height AS height, m.isFavorite AS isFavorite, " +
            "(SELECT COUNT(*) FROM face f WHERE f.mediaId = m.id AND f.ignored = 0) AS faces " +
            "FROM media m WHERE ${conditions.joinToString(" AND ")} ORDER BY m.takenAt, m.id"
        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
