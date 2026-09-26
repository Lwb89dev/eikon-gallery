package app.eikon.gallery.data.db.encryption

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Deleting the readable database once the encrypted one has taken its place. */
object SecureFiles {
    private val SIDE_FILES = listOf("-wal", "-shm", "-journal")

    /** The database file and the files SQLite keeps next to it. */
    fun family(database: File): List<File> = listOf(database) + SIDE_FILES.map { File(database.path + it) }

    /**
     * Overwrites each file with zeros, then deletes it. Flash storage may keep the old blocks where they were (that is up to the file system): this makes the old content much harder to
     * find, it does not make it impossible. Returns whether every file is gone; it never throws, so that a failure here cannot stop the app from starting.
     */
    fun wipe(files: List<File>): Boolean = files.map(::wipeOne).all { it }

    private fun wipeOne(file: File): Boolean {
        if (!file.exists()) return true
        try {
            zero(file)
        } catch (_: IOException) {
            // Deleting it is still worth doing.
        }
        return file.delete() || !file.exists()
    }

    private fun zero(file: File) {
        val block = ByteArray(BLOCK)
        RandomAccessFile(file, "rw").use { raf ->
            var left = raf.length()
            while (left > 0) {
                val n = minOf(left, BLOCK.toLong()).toInt()
                raf.write(block, 0, n)
                left -= n
            }
            raf.fd.sync()
        }
    }

    private const val BLOCK = 64 * 1024
}
