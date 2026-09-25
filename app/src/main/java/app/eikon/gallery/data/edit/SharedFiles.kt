package app.eikon.gallery.data.edit

import java.io.File

/**
 * The temporary pictures made to share an edit, kept in the app's cache folder (never in the library) and handed out through a FileProvider.
 * Each share gets a folder of its own, so two photos with the same name cannot collide, and folders are removed once they are old enough
 * that the receiving app is long done with them.
 */
object SharedFiles {
    /** Long enough for any receiving app to have finished reading the file, short enough that pictures do not pile up. */
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000

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
        return base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_") + "_edit.jpg"
    }

    /** Removes every batch in [root] last changed more than [maxAgeMs] before [now]. Returns how many were removed. */
    fun sweep(root: File, now: Long, maxAgeMs: Long = MAX_AGE_MS): Int {
        val batches = root.listFiles { file -> file.isDirectory } ?: return 0
        return batches.count { batch -> now - batch.lastModified() > maxAgeMs && batch.deleteRecursively() }
    }
}
