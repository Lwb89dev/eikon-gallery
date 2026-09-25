package app.eikon.gallery.data.metadata

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.eikon.gallery.core.permissions.MediaAccessChecker
import app.eikon.gallery.domain.CameraDetails
import app.eikon.gallery.domain.ExifFormat
import app.eikon.gallery.domain.GeoPoint
import app.eikon.gallery.domain.LocationInfo
import app.eikon.gallery.domain.MediaDetails
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.VideoDetails
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the real metadata of one file on demand (when the info panel opens). Nothing here is cached
 * or written anywhere: GPS and camera data are shown to the user and forgotten, never stored in the
 * index.
 *
 * Android removes GPS from what apps read unless the app holds ACCESS_MEDIA_LOCATION *and* asks for
 * the original file with [MediaStore.setRequireOriginal]; both are done here, and only when the user
 * has granted that permission.
 */
@Singleton
class MediaDetailsReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessChecker: MediaAccessChecker,
) {
    suspend fun read(item: MediaItem): MediaDetails = withContext(Dispatchers.IO) {
        val canReadLocation = accessChecker.canReadLocation()
        val uri = if (canReadLocation) requireOriginal(item.uri) else item.uri
        if (item.isVideo) readVideo(item, uri, canReadLocation) else readImage(item, uri, canReadLocation)
    }

    private fun readImage(item: MediaItem, uri: Uri, canReadLocation: Boolean): MediaDetails {
        val exif = openExif(uri) ?: openExif(item.uri)
        val bounds = decodeBounds(item.uri)
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            ?: ExifInterface.ORIENTATION_UNDEFINED
        val (width, height) = displaySize(item, bounds, orientation)
        val coordinates = exif?.latLong?.let { ExifFormat.geoPoint(it[0], it[1]) }
        return baseDetails(item, width, height).copy(
            captureTime = exif?.let(::captureTime),
            colorProfile = bounds?.colorSpaceName ?: exif?.let(::exifColorSpace),
            location = locationInfo(coordinates, canReadLocation),
            camera = exif?.let { cameraDetails(it, orientation) }?.takeUnless { it.isEmpty },
        )
    }

    private fun readVideo(item: MediaItem, uri: Uri, canReadLocation: Boolean): MediaDetails {
        val probe = probeContainer(uri)
        val tracks = probeTracks(uri)
        val durationMs = probe.durationMs.takeIf { it > 0 } ?: item.durationMs
        val frameRate = tracks.frameRate ?: probe.averageFrameRate(durationMs)
        val (width, height) = if (probe.rotation == 90 || probe.rotation == 270) {
            probe.height to probe.width
        } else {
            probe.width to probe.height
        }
        return baseDetails(item, width.takeIf { it > 0 } ?: item.width, height.takeIf { it > 0 } ?: item.height).copy(
            location = locationInfo(ExifFormat.parseIso6709(probe.location), canReadLocation),
            video = VideoDetails(
                durationMs = durationMs,
                frameRate = ExifFormat.frameRate(frameRate),
                videoCodec = ExifFormat.videoCodec(tracks.videoMime),
                audioCodec = ExifFormat.audioCodec(tracks.audioMime),
                bitrate = ExifFormat.bitrate(probe.bitrate),
                dynamicRange = tracks.dynamicRange,
            ),
        )
    }

    private fun baseDetails(item: MediaItem, width: Int, height: Int) = MediaDetails(
        fileName = item.displayName,
        mimeType = item.mimeType,
        folder = item.relativePath?.trimEnd('/')?.takeIf { it.isNotEmpty() },
        album = item.bucketName,
        width = width,
        height = height,
        sizeBytes = item.sizeBytes,
        takenAt = item.takenAt,
        captureTime = null,
        modifiedAt = item.modifiedAt,
        colorProfile = null,
        location = LocationInfo.Hidden,
        camera = null,
        video = null,
    )

    private fun locationInfo(point: GeoPoint?, canReadLocation: Boolean): LocationInfo = when {
        point != null -> LocationInfo.Available(point)
        canReadLocation -> LocationInfo.None
        else -> LocationInfo.Hidden
    }

    // --- Images -------------------------------------------------------------------------------

    private fun openExif(uri: Uri): ExifInterface? = try {
        context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    }

    private class Bounds(val width: Int, val height: Int, val colorSpaceName: String?)

    private fun decodeBounds(uri: Uri): Bounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: IOException) {
            return null
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return Bounds(options.outWidth, options.outHeight, options.outColorSpace?.name)
    }

    /**
     * BitmapFactory reports stored (unrotated) dimensions for JPEG-style files, so the EXIF rotation
     * is applied by hand. HEIF-family decoders already apply their own rotation box, so those are
     * taken as reported.
     */
    private fun displaySize(item: MediaItem, bounds: Bounds?, exifOrientation: Int): Pair<Int, Int> {
        val width = bounds?.width ?: item.width
        val height = bounds?.height ?: item.height
        val decoderRotates = item.mimeType.contains("heif", ignoreCase = true) ||
            item.mimeType.contains("heic", ignoreCase = true) ||
            item.mimeType.contains("avif", ignoreCase = true)
        val swap = !decoderRotates && ExifFormat.orientationSwapsAxes(exifOrientation)
        return if (swap) height to width else width to height
    }

    private fun captureTime(exif: ExifInterface) = ExifFormat.captureTime(
        dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
        offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME),
    )

    private fun exifColorSpace(exif: ExifInterface): String? =
        when (exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, 0)) {
            1 -> "sRGB"
            else -> null
        }

    private fun cameraDetails(exif: ExifInterface, orientation: Int): CameraDetails {
        val flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
        return CameraDetails(
            device = ExifFormat.deviceName(
                exif.getAttribute(ExifInterface.TAG_MAKE),
                exif.getAttribute(ExifInterface.TAG_MODEL),
            ),
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            focalLength = ExifFormat.focalLength(exif.number(ExifInterface.TAG_FOCAL_LENGTH)),
            aperture = ExifFormat.aperture(exif.number(ExifInterface.TAG_F_NUMBER)),
            shutter = ExifFormat.shutter(exif.number(ExifInterface.TAG_EXPOSURE_TIME)),
            iso = ExifFormat.iso(exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)),
            exposureBias = ExifFormat.exposureBias(exif.number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)),
            flashFired = if (flash < 0) null else (flash and FLASH_FIRED_BIT) == FLASH_FIRED_BIT,
            orientation = ExifFormat.orientation(orientation),
            software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** EXIF rationals as a number, or null when the tag is absent (0 is a legitimate value for some). */
    private fun ExifInterface.number(tag: String): Double? {
        if (!hasAttribute(tag)) return null
        return getAttributeDouble(tag, Double.NaN).takeUnless { it.isNaN() }
    }

    // --- Videos -------------------------------------------------------------------------------

    private class ContainerProbe(
        val width: Int = 0,
        val height: Int = 0,
        val rotation: Int = 0,
        val durationMs: Long = 0,
        val bitrate: Long? = null,
        val frameCount: Long = 0,
        val location: String? = null,
    ) {
        fun averageFrameRate(durationMs: Long): Double? =
            if (frameCount > 0 && durationMs > 0) frameCount * MILLIS_PER_SECOND / durationMs else null

        private companion object {
            const val MILLIS_PER_SECOND = 1000.0
        }
    }

    private fun probeContainer(uri: Uri): ContainerProbe {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            ContainerProbe(
                width = retriever.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = retriever.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation = retriever.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                durationMs = retriever.long(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0,
                bitrate = retriever.long(MediaMetadataRetriever.METADATA_KEY_BITRATE),
                frameCount = retriever.long(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT) ?: 0,
                location = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION),
            )
        } catch (_: RuntimeException) {
            ContainerProbe()
        } finally {
            retriever.release()
        }
    }

    private fun MediaMetadataRetriever.long(key: Int): Long? = extractMetadata(key)?.toLongOrNull()

    private fun MediaMetadataRetriever.int(key: Int): Int = extractMetadata(key)?.toIntOrNull() ?: 0

    private class TrackProbe(
        val videoMime: String? = null,
        val audioMime: String? = null,
        val frameRate: Double? = null,
        val dynamicRange: String? = null,
    )

    private fun probeTracks(uri: Uri): TrackProbe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.firstOrNull { it.mime()?.startsWith("video/") == true }
            TrackProbe(
                videoMime = video?.mime(),
                audioMime = formats.firstOrNull { it.mime()?.startsWith("audio/") == true }?.mime(),
                frameRate = video?.frameRate(),
                dynamicRange = video?.let(::dynamicRange),
            )
        } catch (_: IOException) {
            TrackProbe()
        } catch (_: RuntimeException) {
            TrackProbe()
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.mime(): String? = getString(MediaFormat.KEY_MIME)

    /** KEY_FRAME_RATE is an int in some files and a float in others. */
    private fun MediaFormat.frameRate(): Double? {
        if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return null
        return try {
            getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
        } catch (_: ClassCastException) {
            getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
        }
    }

    private fun dynamicRange(format: MediaFormat): String? {
        if (format.mime() == "video/dolby-vision") return "Dolby Vision"
        if (!format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) return null
        return when (format.getInteger(MediaFormat.KEY_COLOR_TRANSFER)) {
            MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10"
            MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }

    private fun requireOriginal(uri: Uri): Uri = try {
        MediaStore.setRequireOriginal(uri)
    } catch (_: UnsupportedOperationException) {
        uri
    }

    private companion object {
        /** Bit 0 of the EXIF Flash tag: the flash fired. */
        const val FLASH_FIRED_BIT = 1
    }
}
