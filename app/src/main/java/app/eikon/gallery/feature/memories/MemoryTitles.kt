package app.eikon.gallery.feature.memories

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.eikon.gallery.R
import app.eikon.gallery.domain.memories.MemoryId
import app.eikon.gallery.domain.memories.MemoryKind
import app.eikon.gallery.domain.memories.MemorySeason
import java.time.ZoneId

/** What a memory is called: "Weekend a Roma", "Estate 2026", "Giulia, 2025". */
@Composable
fun memoryTitle(id: MemoryId, personName: String?): String = when (id) {
    is MemoryId.OnThisDay -> stringResource(R.string.memory_on_this_day)
    is MemoryId.YearAgo -> stringResource(R.string.memory_year_ago)
    is MemoryId.Away -> awayTitle(id)
    is MemoryId.SeasonOf -> stringResource(R.string.memory_season, stringResource(seasonName(id.season)), id.year)
    is MemoryId.WithPerson -> stringResource(R.string.memory_person, personName ?: stringResource(R.string.person_unnamed), id.year)
}

@Composable
private fun awayTitle(id: MemoryId.Away): String = when (id.kind) {
    MemoryKind.WEEKEND -> id.label?.let { stringResource(R.string.memory_weekend_in, it) } ?: stringResource(R.string.memory_weekend)
    MemoryKind.DAY_TRIP -> id.label?.let { stringResource(R.string.memory_day_trip_to, it) } ?: stringResource(R.string.memory_day_trip)
    else -> id.label ?: stringResource(R.string.trip_untitled)
}

private fun seasonName(season: MemorySeason): Int = when (season) {
    MemorySeason.WINTER -> R.string.season_winter
    MemorySeason.SPRING -> R.string.season_spring
    MemorySeason.SUMMER -> R.string.season_summer
    MemorySeason.AUTUMN -> R.string.season_autumn
}

/** The dates a memory covers, in words: "12–18 August 2026", or "2024 · 2022" for an anniversary. */
@Composable
fun memorySubtitle(id: MemoryId): String {
    val context = LocalContext.current
    if (id is MemoryId.OnThisDay) return id.years.joinToString(" · ")
    if (id is MemoryId.WithPerson) return ""
    val range = id.periods(ZoneId.systemDefault()).first()
    return DateUtils.formatDateRange(context, range.startMillis, range.endMillis - 1, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
}
