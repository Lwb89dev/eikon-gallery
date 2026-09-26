package app.eikon.gallery.data.edit

import java.io.File

/**
 * The temporary pictures made to share an edit, kept in the app's cache folder (never in the library) and handed out through a FileProvider.
 * Each share gets a folder of its own, so two photos with the same name cannot collide, and folders are removed once they are old enough
 * that the receiving app is long done with them: at the next share, and every time the app starts, so that a picture never outlives its use by long.
 */
object SharedFiles {
    /** The folder, inside the app's cache, that the FileProvider serves (see res/xml/shared_paths.xml). */
    const val FOLDER = "shared"

    /** Long enough for any receiving app to have finished reading the file (it holds the permission only while it is working on it), short enough that pictures do not linger. */
    const val MAX_AGE_MS = 60L * 60 * 1000

    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

    /** A new, empty folder for one share, inside [root]. */
    fun newBatch(root: File, now: Long): File {
        var index = 0
        while (true) {
            val batch = File(root, "$now-$index")
            if (batch.mkdirs()) return batch
            check(batch.isDirectory) { "cannot create $batch" }
            index++
        }
    }

    /** The name a shared edit is given: the original's, without its extension, plus "_edit" and ".jpg". */
    fun fileName(displayName: String): String {
        val base = displayName.substringBeforeLast('.').ifBlank { "photo" }
        return base.replace(UNSAFE, "_") + "_edit.jpg"
    }

    /** Removes every batch in [root] last changed more than [maxAgeMs] before [now]. Returns how many were removed. */
    fun sweep(root: File, now: Long, maxAgeMs: Long = MAX_AGE_MS): Int {
        val batches = root.listFiles { file -> file.isDirectory } ?: return 0
        return batches.count { batch -> now - batch.lastModified() > maxAgeMs && batch.deleteRecursively() }
    }
}
