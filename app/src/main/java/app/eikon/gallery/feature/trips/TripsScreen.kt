package app.eikon.gallery.feature.trips

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.eikon.gallery.R
import app.eikon.gallery.core.image.MediaThumbnail
import app.eikon.gallery.data.places.TripItem
import app.eikon.gallery.data.places.TripsRepository
import app.eikon.gallery.domain.GridSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The trips found so far; null while they are being worked out. */
@HiltViewModel
class TripsViewModel @Inject constructor(private val repository: TripsRepository) : ViewModel() {
    private val loaded = MutableStateFlow<List<TripItem>?>(null)
    val trips: StateFlow<List<TripItem>?> = loaded.asStateFlow()

    fun load() {
        viewModelScope.launch { loaded.value = repository.trips() }
    }
}

/** Trips eikon found from where and when the photos were taken, with the reason each one counts as a trip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    onBack: () -> Unit,
    onOpen: (GridSource) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TripsViewModel = hiltViewModel(),
) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trips_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { inner ->
        val list = trips
        when {
            list == null -> Box(Modifier.padding(inner).fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyTrips(onOpenSettings, Modifier.padding(inner))
            else -> TripList(list, onOpen, inner)
        }
    }
}

@Composable
private fun TripList(trips: List<TripItem>, onOpen: (GridSource) -> Unit, inner: PaddingValues) {
    val byYear = trips.groupBy { it.trip.firstDay.year }
    LazyColumn(
        contentPadding = PaddingValues(top = inner.calculateTopPadding() + 8.dp, bottom = inner.calculateBottomPadding() + 16.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        byYear.forEach { (year, inYear) ->
            item(key = "year-$year") { Text(year.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
            items(inYear.size, key = { "trip-${inYear[it].trip.startMillis}" }) { TripCard(inYear[it], onOpen) }
        }
    }
}

@Composable
private fun TripCard(item: TripItem, onOpen: (GridSource) -> Unit) {
    val context = LocalContext.current
    val trip = item.trip
    val dates = DateUtils.formatDateRange(context, trip.startMillis, trip.endMillis - 1, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(GridSource.Period(trip.startMillis, trip.endMillis, item.title)) },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)).padding(0.dp)) {
            item.cover?.let { MediaThumbnail(it, Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(item.title ?: stringResource(R.string.trip_untitled), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(dates, style = MaterialTheme.typography.bodyMedium)
            Text(
                pluralStringResource(R.plurals.trip_summary_days, trip.days, trip.days) + " · " + pluralStringResource(R.plurals.items_count, item.itemCount, item.itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.trip_reason, trip.distanceKm, item.homeName ?: stringResource(R.string.trip_home_unknown)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyTrips(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.trips_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.people_go_settings)) }
    }
}
