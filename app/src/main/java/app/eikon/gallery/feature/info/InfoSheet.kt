package app.eikon.gallery.feature.info

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.StringRes
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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.eikon.gallery.R
import app.eikon.gallery.data.embedding.PhotoLabels
import app.eikon.gallery.data.embedding.PhotoSubject
import app.eikon.gallery.data.metadata.IndexedInfo
import app.eikon.gallery.data.metadata.MetadataChange
import app.eikon.gallery.data.metadata.MetadataException
import app.eikon.gallery.domain.CameraDetails
import app.eikon.gallery.domain.CaptureTime
import app.eikon.gallery.domain.ExifFormat
import app.eikon.gallery.domain.GeoPoint
import app.eikon.gallery.domain.LocationInfo
import app.eikon.gallery.domain.MediaDetails
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.PetKind
import app.eikon.gallery.domain.places.WorldMap
import app.eikon.gallery.domain.VideoDetails
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private enum class InfoDialog { NONE, CAPTION, DAY, TIME, LOCATION }

/** What the sections of the sheet can ask for; the sheet decides what each does. */
private class InfoActions(
    val onAllowLocation: () -> Unit,
    val onEditCaption: () -> Unit,
    val onEditDate: () -> Unit,
    val onEditLocation: () -> Unit,
    val onRevertDate: () -> Unit,
    val onRevertLocation: () -> Unit,
    val onRestore: () -> Unit,
)

/**
 * Bottom sheet with the real metadata of [item], read from the file when it opens, and what eikon knows about the photo. Unless [editable] is false (the trash), the caption, the
 * date and the location can be changed; the date and the location are written into the photo's file only after the system asks the user, only for formats that can be written,
 * and can be put back as they were.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(
    item: MediaItem,
    onDismiss: () -> Unit,
    editable: Boolean = true,
    viewModel: InfoViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val labels by viewModel.labels.collectAsStateWithLifecycle()
    val world by viewModel.world.collectAsStateWithLifecycle()
    var dialog by rememberSaveable { mutableStateOf(InfoDialog.NONE) }
    var pickedDay by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(item.id) { viewModel.load(item) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.load(item) }
    val writeRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onSystemRequestFinished(item, it.resultCode == Activity.RESULT_OK)
    }
    InfoEventEffects(viewModel) { sender -> writeRequest.launch(IntentSenderRequest.Builder(sender).build()) }
    val actions = InfoActions(
        onAllowLocation = { locationPermission.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) },
        onEditCaption = { dialog = InfoDialog.CAPTION },
        onEditDate = { dialog = InfoDialog.DAY },
        onEditLocation = { dialog = InfoDialog.LOCATION },
        onRevertDate = { viewModel.change(item, MetadataChange.RevertDate) },
        onRevertLocation = { viewModel.change(item, MetadataChange.RevertLocation) },
        onRestore = { viewModel.restoreInterrupted(item) },
    )
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
            InfoBody(state, labels, world, editable, actions)
        }
    }
    (state as? InfoState.Loaded)?.let { loaded ->
        InfoDialogs(item, loaded, dialog, pickedDay, viewModel, onClose = { dialog = InfoDialog.NONE }, onDayPicked = { pickedDay = it; dialog = InfoDialog.TIME })
    }
}

/** Says, once, how a change to the file went. */
@Composable
private fun InfoEventEffects(viewModel: InfoViewModel, launch: (IntentSender) -> Unit) {
    val context = LocalContext.current
    val currentLaunch by rememberUpdatedState(launch)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event -> handle(event, context, currentLaunch) }
    }
}

private fun handle(event: InfoEvent, context: Context, launch: (IntentSender) -> Unit) {
    when (event) {
        is InfoEvent.LaunchSystemRequest -> launch(event.sender)
        InfoEvent.ChangeDone -> Toast.makeText(context, R.string.metadata_done, Toast.LENGTH_SHORT).show()
        is InfoEvent.ChangeFailed -> Toast.makeText(context, failureText(event.reason), Toast.LENGTH_LONG).show()
    }
}

private fun failureText(reason: MetadataException.Reason): Int = when (reason) {
    MetadataException.Reason.NOT_WRITABLE -> R.string.metadata_not_writable
    MetadataException.Reason.NEEDS_LOCATION_PERMISSION -> R.string.metadata_needs_permission
    MetadataException.Reason.NOTHING_TO_REVERT -> R.string.metadata_nothing_to_revert
    MetadataException.Reason.INTERRUPTED -> R.string.metadata_interrupted
    MetadataException.Reason.FAILED -> R.string.metadata_failed
}

/** The dialogs of the sheet, one at a time. */
@Composable
private fun InfoDialogs(
    item: MediaItem,
    loaded: InfoState.Loaded,
    dialog: InfoDialog,
    pickedDay: Long?,
    viewModel: InfoViewModel,
    onClose: () -> Unit,
    onDayPicked: (Long) -> Unit,
) {
    val cities by viewModel.cities.collectAsStateWithLifecycle()
    val capture = loaded.details.captureTime
    val start = capture?.local ?: LocalDateTime.ofInstant(Instant.ofEpochMilli(loaded.details.takenAt), ZoneId.systemDefault())
    when (dialog) {
        InfoDialog.NONE -> Unit
        InfoDialog.CAPTION -> CaptionDialog(loaded.indexed.caption.orEmpty(), onSave = { viewModel.setCaption(item, it); onClose() }, onDismiss = onClose)
        InfoDialog.DAY -> DayDialog(start.toLocalDate(), onPicked = { onDayPicked(it.toEpochDay()) }, onDismiss = onClose)
        InfoDialog.TIME -> TimeDialog(
            start.toLocalTime(),
            onPicked = { time ->
                val local = LocalDateTime.of(LocalDate.ofEpochDay(pickedDay ?: start.toLocalDate().toEpochDay()), time)
                val offset = capture?.offset ?: ZoneId.systemDefault().rules.getOffset(local)
                viewModel.change(item, MetadataChange.Date(local, offset))
                onClose()
            },
            onDismiss = onClose,
        )
        InfoDialog.LOCATION -> LocationDialog(
            current = (loaded.details.location as? LocationInfo.Available)?.point,
            cities = cities,
            onSearch = viewModel::searchCities,
            onSave = { viewModel.change(item, MetadataChange.Location(it)); viewModel.clearCities(); onClose() },
            onRemove = { viewModel.change(item, MetadataChange.Location(null)); viewModel.clearCities(); onClose() },
            onDismiss = { viewModel.clearCities(); onClose() },
        )
    }
}

@Composable
private fun InfoBody(state: InfoState, labels: PhotoLabels?, world: WorldMap?, editable: Boolean, actions: InfoActions) {
    when (state) {
        InfoState.Loading -> Muted(stringResource(R.string.info_loading))
        InfoState.Failed -> Muted(stringResource(R.string.info_error))
        is InfoState.Loaded -> DetailsList(state, labels, world, editable, actions)
    }
}

@Composable
private fun DetailsList(state: InfoState.Loaded, labels: PhotoLabels?, world: WorldMap?, editable: Boolean, actions: InfoActions) {
    val details = state.details
    val indexed = state.indexed
    val edit = if (editable) state.editable else null
    if (edit?.interrupted == true) InterruptedNotice(actions.onRestore)
    FileSection(details)
    CaptionSection(indexed.caption, editable, actions.onEditCaption)
    DateSection(details, edit, actions)
    LocationRow(details.location, world, edit, actions)
    InfoRow(R.string.info_place, indexed.place)
    InfoRow(R.string.info_people, peopleLabel(indexed))
    InfoRow(R.string.info_albums, indexed.albums.takeIf { it.isNotEmpty() }?.joinToString(", "))
    labels?.let { LabelRows(it) }
    details.camera?.let { CameraSection(it) }
    details.video?.let { VideoSection(it) }
    indexed.text?.let { TextFoundSection(it) }
}

@Composable
private fun InterruptedNotice(onRestore: () -> Unit) {
    Text(stringResource(R.string.metadata_interrupted), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    TextButton(onClick = onRestore) { Text(stringResource(R.string.metadata_restore_file)) }
    Divider()
}

/** What the analysis says the photo probably shows, in the words of the current language. Only when it has something to say. */
@Composable
private fun LabelRows(labels: PhotoLabels) {
    val pets = labels.pets.map { stringResource(if (it == PetKind.DOG) R.string.pet_dog else R.string.pet_cat) }
    InfoRow(R.string.info_pets, pets.takeIf { it.isNotEmpty() }?.joinToString(", "))
    val subjects = labels.subjects.map { stringResource(subjectName(it)) }
    InfoRow(R.string.info_shows, subjects.takeIf { it.isNotEmpty() }?.joinToString(", "))
}

/** Named one by one (not looked up by name at run time) so the release build's resource shrinker can see that every string is used. */
@StringRes
private fun subjectName(subject: PhotoSubject): Int = when (subject) {
    PhotoSubject.LANDSCAPE -> R.string.label_landscape
    PhotoSubject.BEACH -> R.string.label_beach
    PhotoSubject.SEA -> R.string.label_sea
    PhotoSubject.MOUNTAIN -> R.string.label_mountain
    PhotoSubject.SUNSET -> R.string.label_sunset
    PhotoSubject.SNOW -> R.string.label_snow
    PhotoSubject.CITY -> R.string.label_city
    PhotoSubject.BUILDING -> R.string.label_building
    PhotoSubject.STREET -> R.string.label_street
    PhotoSubject.WOODS -> R.string.label_woods
    PhotoSubject.FLOWER -> R.string.label_flower
    PhotoSubject.SKY -> R.string.label_sky
    PhotoSubject.PERSON -> R.string.label_person
    PhotoSubject.GROUP -> R.string.label_group
    PhotoSubject.FOOD -> R.string.label_food
    PhotoSubject.DESSERT -> R.string.label_dessert
    PhotoSubject.DRINK -> R.string.label_drink
    PhotoSubject.CAR -> R.string.label_car
    PhotoSubject.BICYCLE -> R.string.label_bicycle
    PhotoSubject.AIRPLANE -> R.string.label_airplane
    PhotoSubject.BOAT -> R.string.label_boat
    PhotoSubject.BIRD -> R.string.label_bird
    PhotoSubject.HORSE -> R.string.label_horse
    PhotoSubject.INTERIOR -> R.string.label_interior
    PhotoSubject.NIGHT -> R.string.label_night
    PhotoSubject.PARTY -> R.string.label_party
    PhotoSubject.SPORT -> R.string.label_sport
    PhotoSubject.ANIMAL -> R.string.label_animal
    PhotoSubject.DOG, PhotoSubject.CAT -> R.string.info_pets // never offered as a subject
}

@Composable
private fun CaptionSection(caption: String?, editable: Boolean, onEdit: () -> Unit) {
    if (caption != null) InfoRow(R.string.info_caption, caption)
    if (editable) TextButton(onClick = onEdit) { Text(stringResource(if (caption == null) R.string.caption_add else R.string.caption_edit)) }
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
private fun DateSection(details: MediaDetails, edit: EditableMetadata?, actions: InfoActions) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY
    val taken = details.captureTime?.let { formatCapture(it, locale) }
        ?: DateUtils.formatDateTime(context, details.takenAt, flags)
    InfoRow(R.string.info_date, taken)
    InfoRow(R.string.info_modified, DateUtils.formatDateTime(context, details.modifiedAt, flags))
    if (edit?.canEdit == true) TextButton(onClick = actions.onEditDate) { Text(stringResource(R.string.date_change)) }
    if (edit?.canRevertDate == true) TextButton(onClick = actions.onRevertDate) { Text(stringResource(R.string.date_revert)) }
    if (edit?.needsLocationPermission == true) NeedsPermission(actions.onAllowLocation)
}

/** Changing a photo's file needs the photo-location permission (see MetadataWriter), so the choice is offered with a way to give it. */
@Composable
private fun NeedsPermission(onAllow: () -> Unit) {
    Muted(stringResource(R.string.metadata_needs_permission))
    TextButton(onClick = onAllow) { Text(stringResource(R.string.info_location_allow)) }
}

/** Camera wall-clock time as recorded, with its UTC offset when the file has one. */
private fun formatCapture(capture: CaptureTime, locale: Locale): String {
    val text = capture.local.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
    return capture.offset?.let { "$text (UTC$it)" } ?: text
}

@Composable
private fun LocationRow(location: LocationInfo, world: WorldMap?, edit: EditableMetadata?, actions: InfoActions) {
    Divider()
    when (location) {
        is LocationInfo.Available -> AvailableLocation(location.point, world)
        LocationInfo.None -> InfoRow(R.string.info_location, stringResource(R.string.info_location_none))
        LocationInfo.Hidden -> HiddenLocation(actions.onAllowLocation)
    }
    if (edit?.canEdit == true) TextButton(onClick = actions.onEditLocation) { Text(stringResource(R.string.location_change)) }
    if (edit?.canRevertLocation == true) TextButton(onClick = actions.onRevertLocation) { Text(stringResource(R.string.location_revert)) }
}

@Composable
private fun AvailableLocation(point: GeoPoint, world: WorldMap?) {
    val context = LocalContext.current
    val coordinates = String.format(Locale.ROOT, "%.5f, %.5f", point.latitude, point.longitude)
    InfoRow(R.string.info_location, coordinates)
    // The map is drawn from country outlines inside the app and no tile is fetched: eikon has no internet access in its standard build. The user's own maps app is one tap away.
    world?.let { LocationPreview(it, point, Modifier.padding(vertical = 8.dp)) }
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

/** Text read from the photo by the background analysis: selectable, and one tap to copy it all. */
@Composable
private fun TextFoundSection(text: String) {
    val context = LocalContext.current
    Divider()
    Section(R.string.info_text_found)
    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
    TextButton(onClick = { copyToClipboard(context, text) }) { Text(stringResource(R.string.info_text_copy)) }
}

/** Text read from a photo can be a receipt or a document, so the clip is marked sensitive: Android then keeps it out of the clipboard preview on screen. */
private fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText(null, text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
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

/** "Marco, Giulia, 1 unnamed", or null when no face was found in the photo. */
@Composable
private fun peopleLabel(indexed: IndexedInfo): String? {
    val unnamed = if (indexed.unnamedFaces > 0) {
        listOf(pluralStringResource(R.plurals.info_people_unnamed_count, indexed.unnamedFaces, indexed.unnamedFaces))
    } else {
        emptyList()
    }
    val parts = indexed.people + unnamed
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}
