package app.eikon.gallery.feature.info

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.domain.CameraDetails
import app.eikon.gallery.domain.CaptureTime
import app.eikon.gallery.domain.ExifFormat
import app.eikon.gallery.domain.GeoPoint
import app.eikon.gallery.domain.LocationInfo
import app.eikon.gallery.domain.MediaDetails
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.VideoDetails
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Bottom sheet with the real metadata of [item], read from the file when it opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    viewModel: InfoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(item.id) { viewModel.load(item) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.load(item)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.info_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            InfoBody(state) { locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
        }
    }
}

@Composable
private fun InfoBody(state: InfoState, onAllowLocation: () -> Unit) {
    when (state) {
        InfoState.Loading -> Muted(stringResource(R.string.info_loading))
        InfoState.Failed -> Muted(stringResource(R.string.info_error))
        is InfoState.Loaded -> DetailsList(state.details, onAllowLocation)
    }
}

@Composable
private fun DetailsList(details: MediaDetails, onAllowLocation: () -> Unit) {
    FileSection(details)
    DateSection(details)
    LocationRow(details.location, onAllowLocation)
    details.camera?.let { CameraSection(it) }
    details.video?.let { VideoSection(it) }
}

@Composable
private fun FileSection(details: MediaDetails) {
    val context = LocalContext.current
    Section(R.string.info_section_file)
    InfoRow(R.string.info_name, details.fileName)
    InfoRow(R.string.info_type, details.mimeType)
    InfoRow(R.string.info_resolution, ExifFormat.resolution(details.width, details.height))
    if (details.video == null) InfoRow(R.string.info_megapixels, ExifFormat.megapixels(details.width, details.height))
    InfoRow(R.string.info_size, Formatter.formatFileSize(context, details.sizeBytes))
    InfoRow(R.string.info_color_profile, details.colorProfile)
    InfoRow(R.string.info_folder, details.folder)
    InfoRow(R.string.info_album, details.album)
}

@Composable
private fun DateSection(details: MediaDetails) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY
    val taken = details.captureTime?.let { formatCapture(it, locale) }
        ?: DateUtils.formatDateTime(context, details.takenAt, flags)
    InfoRow(R.string.info_date, taken)
    InfoRow(R.string.info_modified, DateUtils.formatDateTime(context, details.modifiedAt, flags))
}

/** Camera wall-clock time as recorded, with its UTC offset when the file has one. */
private fun formatCapture(capture: CaptureTime, locale: Locale): String {
    val text = capture.local.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
    return capture.offset?.let { "$text (UTC$it)" } ?: text
}

@Composable
private fun LocationRow(location: LocationInfo, onAllowLocation: () -> Unit) {
    Divider()
    when (location) {
        is LocationInfo.Available -> AvailableLocation(location.point)
        LocationInfo.None -> InfoRow(R.string.info_location, stringResource(R.string.info_location_none))
        LocationInfo.Hidden -> HiddenLocation(onAllowLocation)
    }
}

@Composable
private fun AvailableLocation(point: GeoPoint) {
    val context = LocalContext.current
    val coordinates = String.format(Locale.ROOT, "%.5f, %.5f", point.latitude, point.longitude)
    InfoRow(R.string.info_location, coordinates)
    // No map tile is fetched: eikon has no internet access. The user's own maps app is opened instead.
    TextButton(onClick = { openInMaps(context, point) }) { Text(stringResource(R.string.info_location_open)) }
}

@Composable
private fun HiddenLocation(onAllow: () -> Unit) {
    Label(R.string.info_location)
    Muted(stringResource(R.string.info_location_hidden))
    TextButton(onClick = onAllow) { Text(stringResource(R.string.info_location_allow)) }
}

private fun openInMaps(context: android.content.Context, point: GeoPoint) {
    val coordinates = String.format(Locale.ROOT, "%.6f,%.6f", point.latitude, point.longitude)
    val intent = Intent(Intent.ACTION_VIEW, "geo:$coordinates?q=$coordinates".toUri())
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No maps app installed: nothing to open.
    }
}

@Composable
private fun CameraSection(camera: CameraDetails) {
    Divider()
    Section(R.string.info_section_camera)
    InfoRow(R.string.info_camera, camera.device)
    InfoRow(R.string.info_lens, camera.lens)
    InfoRow(R.string.info_focal_length, camera.focalLength)
    InfoRow(R.string.info_aperture, camera.aperture)
    InfoRow(R.string.info_shutter, camera.shutter)
    InfoRow(R.string.info_iso, camera.iso)
    InfoRow(R.string.info_exposure_bias, camera.exposureBias)
    camera.flashFired?.let { InfoRow(R.string.info_flash, stringResource(if (it) R.string.info_flash_fired else R.string.info_flash_off)) }
    InfoRow(R.string.info_orientation, camera.orientation)
    InfoRow(R.string.info_software, camera.software)
}

@Composable
private fun VideoSection(video: VideoDetails) {
    Divider()
    Section(R.string.info_section_video)
    InfoRow(R.string.info_duration, ExifFormat.duration(video.durationMs))
    InfoRow(R.string.info_frame_rate, video.frameRate)
    InfoRow(R.string.info_video_codec, video.videoCodec)
    InfoRow(R.string.info_audio_codec, video.audioCodec)
    InfoRow(R.string.info_bitrate, video.bitrate)
    InfoRow(R.string.info_dynamic_range, video.dynamicRange)
}

// --- Building blocks ---------------------------------------------------------------------------

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Section(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun Label(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A label/value line; a null or blank value hides the row entirely instead of showing a dash. */
@Composable
private fun InfoRow(label: Int, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
