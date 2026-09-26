package app.eikon.gallery.feature.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.eikon.gallery.R
import app.eikon.gallery.data.metadata.MetadataRepository
import app.eikon.gallery.data.places.City
import app.eikon.gallery.domain.CoordinateParser
import app.eikon.gallery.domain.GeoPoint
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

/** Where the user writes or changes the caption (kept in eikon only; the photo's file is never touched by it). */
@Composable
fun CaptionDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.caption_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MetadataRepository.MAX_CAPTION) },
                label = { Text(stringResource(R.string.caption_hint)) },
                supportingText = { Text(stringResource(R.string.caption_note)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Step one of changing when a photo was taken: the day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDialog(initial: LocalDate, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = { state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate()) } },
            ) { Text(stringResource(R.string.action_next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) { DatePicker(state) }
}

/** Step two: the time of day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onPicked: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.date_time_title)) },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Where the photo was taken, written as coordinates or chosen from the cities eikon knows offline (nothing is looked up online). Saving writes the position into the photo's
 * file, after the system asks; removing takes it out. Both can be undone.
 */
@Composable
fun LocationDialog(
    current: GeoPoint?,
    cities: List<City>,
    onSearch: (String) -> Unit,
    onSave: (GeoPoint) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current?.let { coordinates(it) }.orEmpty()) }
    var place by rememberSaveable { mutableStateOf("") }
    val parsed = CoordinateParser.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.location_coordinates)) },
                    isError = text.isNotBlank() && parsed == null,
                    supportingText = { Text(stringResource(if (text.isNotBlank() && parsed == null) R.string.location_invalid else R.string.location_coordinates_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = place,
                    onValueChange = { place = it; onSearch(it) },
                    label = { Text(stringResource(R.string.location_city)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                cities.forEach { city -> CityRow(city) { text = coordinates(GeoPoint(city.latitude, city.longitude)) } }
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Column {
                if (current != null) TextButton(onClick = onRemove) { Text(stringResource(R.string.location_remove)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun CityRow(city: City, onPick: () -> Unit) {
    TextButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
        Text("${city.name}, ${city.countryCode}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
    }
}

private fun coordinates(point: GeoPoint): String = String.format(Locale.ROOT, "%.5f, %.5f", point.latitude, point.longitude)
