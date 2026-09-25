package app.eikon.gallery.domain

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** A run of consecutive media sharing one date header. [startIndex] is the index of its first media item. */
@Immutable
data class TimelineSection(val bucket: String, val count: Int, val startIndex: Int)

/**
 * Maps between the two index spaces of the library grid without materialising anything per item:
 * the *media index* (position in the ordered media list, what the pager and paging source use) and
 * the *grid position* (media plus one header cell per section). With 100k items only the sections
 * (a few thousand at most) are held in memory.
 */
@Immutable
class TimelineLayout(val sections: List<TimelineSection>) {
    val mediaCount: Int = sections.lastOrNull()?.let { it.startIndex + it.count } ?: 0
    val gridItemCount: Int = mediaCount + sections.size

    /** Index of the section containing [mediaIndex], or -1 when out of range. */
    fun sectionIndexOfMedia(mediaIndex: Int): Int {
        if (mediaIndex !in 0 until mediaCount) return -1
        return lastSectionWhere { it.startIndex <= mediaIndex }
    }

    fun gridPositionOfMedia(mediaIndex: Int): Int {
        val section = sectionIndexOfMedia(mediaIndex)
        return if (section < 0) -1 else mediaIndex + section + 1
    }

    fun sectionIndexOfGridPosition(position: Int): Int {
        if (position !in 0 until gridItemCount) return -1
        var lo = 0
        var hi = sections.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (headerPosition(mid) <= position) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Media index shown at [position], or null for a header cell (or an out-of-range position). */
    fun mediaIndexOfGridPosition(position: Int): Int? {
        val section = sectionIndexOfGridPosition(position)
        if (section < 0 || position == headerPosition(section)) return null
        return position - section - 1
    }

    fun headerPosition(sectionIndex: Int): Int = sections[sectionIndex].startIndex + sectionIndex

    private inline fun lastSectionWhere(predicate: (TimelineSection) -> Boolean): Int {
        var lo = 0
        var hi = sections.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (predicate(sections[mid])) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        val Empty = TimelineLayout(emptyList())

        /** Builds sections from ordered (bucket, count) pairs, computing each section's start index. */
        fun fromCounts(counts: List<Pair<String, Int>>): TimelineLayout {
            var start = 0
            val sections = counts.map { (bucket, count) ->
                TimelineSection(bucket, count, start).also { start += count }
            }
            return TimelineLayout(sections)
        }
    }
}

/**
 * Turns a section bucket ("2025-08-14" / "2025-08") into the header text. Locale-correct patterns
 * are supplied by [skeletonPatterns] (on Android: DateFormat.getBestDateTimePattern), which keeps
 * this class testable without the framework.
 */
class TimelineLabelFormatter(
    private val today: LocalDate,
    private val locale: Locale,
    private val todayLabel: String,
    private val yesterdayLabel: String,
    skeletonPatterns: (skeleton: String) -> String,
) {
    private val dayThisYear = formatter(skeletonPatterns("EEEEdMMMM"))
    private val dayOtherYear = formatter(skeletonPatterns("yMMMMd"))
    private val month = formatter(skeletonPatterns("yMMMM"))

    fun label(bucket: String, grouping: TimelineGrouping): String = try {
        when (grouping) {
            TimelineGrouping.DAY -> dayLabel(LocalDate.parse(bucket))
            TimelineGrouping.MONTH -> month.format(YearMonth.parse(bucket))
        }
    } catch (_: DateTimeParseException) {
        bucket
    }

    /** Month and year, used by the fast scroller bubble regardless of grouping. */
    fun monthLabel(bucket: String): String = try {
        month.format(YearMonth.parse(bucket.take(MONTH_BUCKET_LENGTH)))
    } catch (_: DateTimeParseException) {
        bucket
    }

    private fun dayLabel(date: LocalDate): String = when {
        date == today -> todayLabel
        date == today.minusDays(1) -> yesterdayLabel
        date.year == today.year -> dayThisYear.format(date)
        else -> dayOtherYear.format(date)
    }

    private fun formatter(pattern: String): DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, locale)

    private companion object {
        const val MONTH_BUCKET_LENGTH = 7
    }
}
