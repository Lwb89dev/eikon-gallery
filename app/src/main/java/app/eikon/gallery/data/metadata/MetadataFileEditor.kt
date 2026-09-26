package app.eikon.gallery.data.metadata

import androidx.exifinterface.media.ExifInterface
import app.eikon.gallery.domain.ExifFields
import app.eikon.gallery.domain.GeoPoint
import java.io.File

/** EXIF editing on a plain file, which is always a *working copy*: the photo itself is written only after the result has been checked (see [MetadataWriter]). */
internal object MetadataFileEditor {
    /** The tags of [tags] that [file] has, as it spells them. */
    fun snapshot(file: File, tags: List<String>): Map<String, String> {
        val exif = ExifInterface(file.absolutePath)
        return tags.mapNotNull { tag -> exif.getAttribute(tag)?.let { tag to it } }.toMap()
    }

    /** Sets every tag of [tags] to its value in [values], or takes it out if [values] has none. */
    fun write(file: File, tags: List<String>, values: Map<String, String>) {
        val exif = ExifInterface(file.absolutePath)
        tags.forEach { exif.setAttribute(it, values[it]) }
        exif.saveAttributes()
    }

    /** Puts [point] in the file, taking out whatever else the file said about its location (an altitude that no longer belongs to the place). */
    fun writeLocation(file: File, point: GeoPoint) {
        val exif = ExifInterface(file.absolutePath)
        ExifFields.GPS_TAGS.forEach { exif.setAttribute(it, null) }
        exif.setLatLong(point.latitude, point.longitude)
        exif.saveAttributes()
    }
}
