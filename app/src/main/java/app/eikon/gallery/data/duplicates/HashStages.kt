package app.eikon.gallery.data.duplicates

import android.content.Context
import android.graphics.ImageDecoder
import app.eikon.gallery.data.db.ContentHashEntity
import app.eikon.gallery.data.db.DuplicatesDao
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.db.PerceptualHashEntity
import app.eikon.gallery.data.embedding.RgbImage
import app.eikon.gallery.data.indexing.StageOutcome
import app.eikon.gallery.data.indexing.StageProcessor
import app.eikon.gallery.domain.mediaContentUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Decodes a photo straight to a small square (aspect ratio ignored, as the fingerprint expects), which is very cheap. */
@Singleton
class HashImageLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun load(mediaId: Long): RgbImage {
        val source = ImageDecoder.createSource(context.contentResolver, mediaContentUri(mediaId, isVideo = false))
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(EDGE, EDGE)
        }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return RgbImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        /** Twice the fingerprint's own size: the decoder's scaling is cheap, and the last halving is done exactly. */
        const val EDGE = 64
    }
}

/** Fingerprints what a photo looks like so copies of it can be found. */
class PerceptualHashStageProcessor @Inject constructor(
    private val loader: HashImageLoader,
    private val dao: DuplicatesDao,
) : StageProcessor {
    override suspend fun process(item: MediaEntity): StageOutcome = withContext(Dispatchers.Default) {
        dao.upsertPerceptualHash(PerceptualHashEntity(item.id, PerceptualHash.of(loader.load(item.id)), item.modifiedAt))
        StageOutcome.DONE
    }
}

/** Fingerprints the bytes of a file (SHA-256), read in blocks, so exact copies can be found. Only run for files that share a size with another. */
class FileHashStageProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DuplicatesDao,
) : StageProcessor {
    override suspend fun process(item: MediaEntity): StageOutcome = withContext(Dispatchers.IO) {
        dao.upsertContentHash(ContentHashEntity(item.id, sha256(item), item.modifiedAt))
        StageOutcome.DONE
    }

    private fun sha256(item: MediaEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openInputStream(mediaContentUri(item.id, item.isVideo)) ?: throw IOException("cannot open ${item.id}")
        stream.use { input ->
            val buffer = ByteArray(BLOCK)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BLOCK = 1 shl 16
    }
}
