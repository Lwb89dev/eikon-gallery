package app.eikon.gallery.domain

import androidx.compose.runtime.Immutable

/** Where a photo/video was taken, as far as Android lets eikon know. */
sealed interface LocationInfo {
    data class Available(val point: GeoPoint) : LocationInfo

    /** The file carries no (usable) location. */
    data object None : LocationInfo

    /** Android redacts GPS from apps that do not hold ACCESS_MEDIA_LOCATION, so absence is unknowable. */
    data object Hidden : LocationInfo
}

/** Everything is pre-formatted (see ExifFormat); a null field is not shown. */
@Immutable
data class CameraDetails(
    val device: String?,
    val lens: String?,
    val focalLength: String?,
    val aperture: String?,
    val shutter: String?,
    val iso: String?,
    val exposureBias: String?,
    val flashFired: Boolean?,
    val orientation: String?,
    val software: String?,
) {
    val isEmpty: Boolean
        get() = listOf(device, lens, focalLength, aperture, shutter, iso, exposureBias, orientation, software)
            .all { it == null } && flashFired == null
}

@Immutable
data class VideoDetails(
    val durationMs: Long,
    val frameRate: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val bitrate: String?,
    val dynamicRange: String?,
)

/** Real metadata read from the file itself (EXIF / container), not from the eikon index. */
@Immutable
data class MediaDetails(
    val fileName: String,
    val mimeType: String,
    val folder: String?,
    val album: String?,
    /** Dimensions as displayed, i.e. after applying the rotation stored in the file. */
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val takenAt: Long,
    val captureTime: CaptureTime?,
    val modifiedAt: Long,
    val colorProfile: String?,
    val location: LocationInfo,
    val camera: CameraDetails?,
    val video: VideoDetails?,
)
