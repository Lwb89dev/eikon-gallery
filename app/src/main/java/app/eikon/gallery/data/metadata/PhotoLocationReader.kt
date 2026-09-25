package app.eikon.gallery.data.metadata

import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import app.eikon.gallery.domain.ExifFormat
import app.eikon.gallery.domain.GeoPoint
import app.eikon.gallery.domain.mediaContentUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads where a photo was taken from its EXIF. Android strips GPS from what apps read unless the app
 * holds ACCESS_MEDIA_LOCATION and asks for the original file, so this only yields anything once the
 * user has granted that permission (the analysis does not run without it).
 */
@Singleton
class PhotoLocationReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Null when the photo has no usable position. Throws [IOException] if the file cannot be read. */
    fun read(mediaId: Long): GeoPoint? {
        val uri = MediaStore.setRequireOriginal(mediaContentUri(mediaId, isVideo = false))
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open media $mediaId")
        return stream.use { ExifInterface(it).latLong?.let { ll -> ExifFormat.geoPoint(ll[0], ll[1]) } }
    }
}
