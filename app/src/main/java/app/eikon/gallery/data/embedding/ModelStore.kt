package app.eikon.gallery.data.embedding

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/** Where the bundled model files come from. */
interface ModelStore {
    /** A read-only direct buffer over the file, so a session can be created without copying it onto the Java heap. */
    fun map(name: String): ByteBuffer

    /** The whole (small) file. */
    fun read(name: String): ByteArray
}

/**
 * Models live in the APK's assets, stored uncompressed (`noCompress` in the build), so they are read in
 * place by memory-mapping the region of the APK: no second copy of 220 MB on the phone's storage. If an
 * asset cannot be opened that way it is copied to the cache once and mapped from there.
 */
@Singleton
class AssetModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModelStore {
    override fun map(name: String): ByteBuffer = try {
        mapAsset(name)
    } catch (_: java.io.FileNotFoundException) {
        mapFile(copyToCache(name))
    }

    override fun read(name: String): ByteArray = context.assets.open("$DIRECTORY/$name").use { it.readBytes() }

    private fun mapAsset(name: String): ByteBuffer =
        context.assets.openFd("$DIRECTORY/$name").use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { input ->
                input.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    /** Compressed assets cannot be mapped in place; this is only the fallback. A half-written copy is never used. */
    private fun copyToCache(name: String): File {
        val target = File(context.cacheDir, "$DIRECTORY/$name")
        if (target.length() > 0) return target
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        context.assets.open("$DIRECTORY/$name").use { input -> partial.outputStream().use { input.copyTo(it) } }
        check(partial.renameTo(target)) { "could not store model $name" }
        return target
    }

    private companion object {
        const val DIRECTORY = "models"
    }
}

/** Maps a whole file read-only. Also used by tests to run the real models from disk. */
fun mapFile(file: File): ByteBuffer =
    RandomAccessFile(file, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }

/** [ModelStore] over a plain directory. */
class DirectoryModelStore(private val directory: File) : ModelStore {
    override fun map(name: String): ByteBuffer = mapFile(File(directory, name))
    override fun read(name: String): ByteArray = File(directory, name).readBytes()
}
