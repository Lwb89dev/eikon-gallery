package app.eikon.gallery.data.backup

import java.time.Instant
import java.time.ZoneOffset

/**
 * Where a file goes on a server that stores plain files (Nextcloud). The name carries the first characters of the file's SHA-1, so:
 * the same picture always lands at the same place (sending it again, from this phone or another, changes nothing), and two different pictures that happen to share
 * a name (`IMG_0001.jpg` from two folders) can never overwrite each other. The folder is the day the photo was taken, in UTC so that it does not depend on the
 * time zone the phone is in when it runs.
 */
object RemoteNames {
    private const val HASH_CHARS = 8
    private const val MAX_STEM = 120
    private val UNSAFE = Regex("[\\u0000-\\u001f\\\\/:*?\"<>|]")

    /** `folder/2025/08/IMG_1234_a1b2c3d4.jpg`, relative to the user's files. */
    fun path(folder: String, file: BackupFile): String {
        val day = Instant.ofEpochMilli(file.takenAt).atOffset(ZoneOffset.UTC)
        val (stem, extension) = split(clean(file.name))
        val name = stem.take(MAX_STEM) + "_" + file.sha1.take(HASH_CHARS) + extension
        return listOf(*segments(folder).toTypedArray(), "%04d".format(day.year), "%02d".format(day.monthValue), name).joinToString("/")
    }

    /** The parts of a folder setting: no empty, dotted or dangerous parts, so it can only ever name a place inside the user's files. */
    fun segments(folder: String): List<String> =
        folder.split('/', '\\').map { clean(it).trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }

    private fun clean(name: String): String = name.replace(UNSAFE, "_")

    private fun split(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name.ifBlank { "photo" } to "" else name.substring(0, dot) to name.substring(dot).lowercase()
    }
}
