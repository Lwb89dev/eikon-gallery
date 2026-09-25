package app.eikon.gallery.feature.search

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.indexing.AnalysisStatus
import app.eikon.gallery.data.indexing.StageProgress
import app.eikon.gallery.data.settings.AnalysisSettings
import app.eikon.gallery.domain.search.DateSpec
import app.eikon.gallery.domain.search.SearchSpec
import app.eikon.gallery.feature.library.labelRes
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Search field plus a line showing how the text was understood, sitting where the top bar normally is. */
@Composable
fun SearchTopBar(
    text: String,
    onTextChange: (String) -> Unit,
    spec: SearchSpec,
    analysis: AnalysisStatus?,
    settings: AnalysisSettings,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(bottom = 4.dp),
    ) {
        SearchField(text, onTextChange)
        Interpretation(spec)
        AnalysisNote(analysis, settings)
    }
}

@Composable
private fun SearchField(text: String, onTextChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    // Opening the tab with nothing typed goes straight to typing.
    LaunchedEffect(Unit) { if (text.isEmpty()) focusRequester.requestFocus() }
    TextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { onTextChange("") }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.search_clear))
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        shape = RoundedCornerShape(28.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focusRequester),
    )
}

/** Chips for what was understood: dates, kinds, places and words. Not interactive; purely explanatory. */
@Composable
private fun Interpretation(spec: SearchSpec) {
    val labels = interpretationLabels(spec)
    if (labels.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        items(labels) { label ->
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun interpretationLabels(spec: SearchSpec): List<String> {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return buildList {
        val filters = spec.filters
        if (filters.type != app.eikon.gallery.domain.TypeFilter.ALL) add(stringResource(filters.type.labelRes()))
        if (filters.favoritesOnly) add(stringResource(R.string.filter_favorites))
        filters.category?.let { add(stringResource(it.labelRes())) }
        spec.dates.forEach { add(dateLabel(it, context, locale)) }
        spec.terms.forEach { add(it.text) }
    }
}

private fun dateLabel(date: DateSpec, context: android.content.Context, locale: Locale): String = when (date) {
    is DateSpec.Range -> DateUtils.formatDateRange(
        context,
        date.range.startMillis,
        date.range.endMillis - 1,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR,
    )
    is DateSpec.Months -> date.months.sorted().joinToString(", ") { Month.of(it).getDisplayName(TextStyle.FULL_STANDALONE, locale) }
    is DateSpec.MonthDay -> "${date.day} ${Month.of(date.month).getDisplayName(TextStyle.FULL, locale)}"
}

/** Tells the user when results may be missing because photos have not been analyzed yet. */
@Composable
private fun AnalysisNote(analysis: AnalysisStatus?, settings: AnalysisSettings) {
    if (!settings.anyEnabled) {
        NoteText(stringResource(R.string.search_analysis_off))
        return
    }
    if (settings.semantic && analysis != null && IndexStage.EMBED in analysis.unavailable) {
        NoteText(stringResource(R.string.search_semantic_unavailable))
    }
    val progress = incompleteProgress(analysis, settings) ?: return
    val percent = if (progress.total == 0) 0 else progress.done * PERCENT / progress.total
    NoteText(stringResource(R.string.search_analysis_note, percent))
}

@Composable
private fun NoteText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

private fun incompleteProgress(analysis: AnalysisStatus?, settings: AnalysisSettings): StageProgress? {
    if (analysis == null || settings.paused) return null
    val enabled = listOfNotNull(
        analysis.places.takeIf { settings.places },
        analysis.semantic.takeIf { settings.semantic },
        analysis.people.takeIf { settings.people },
        analysis.duplicates.takeIf { settings.duplicates },
        analysis.text.takeIf { settings.text },
    )
    val unfinished = enabled.filter { it.total > 0 && !it.isComplete }
    return unfinished.minByOrNull { it.done.toDouble() / it.total }
}

private const val PERCENT = 100

/** Shown instead of results: an invitation to type, or "nothing found". */
@Composable
fun SearchEmpty(hasQuery: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hasQuery) {
            Text(
                text = stringResource(R.string.search_no_results),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 48.dp),
            )
        } else {
            Text(
                text = stringResource(R.string.search_hint_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 32.dp),
            )
            Text(
                text = stringResource(R.string.search_hint_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
