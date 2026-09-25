package app.eikon.gallery.data.mediastore

import android.content.Context
import android.database.Cursor
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.sync.MediaStoreSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reads photos and videos from the MediaStore `files` table of the merged external volume. One
 * table gives a single id space for images and videos, so the same id addresses the row in both.
 * MediaStore itself hides pending and trashed items and, when access is limited to selected photos,
 * everything that was not selected.
 */
@Singleton
class ContentResolverMediaStoreSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaStoreSource {
    private val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    override fun currentGeneration(): Long? = try {
        MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL)
    } catch (_: RuntimeException) {
        // Some platform versions only accept a concrete volume name.
        runCatching { MediaStore.getGeneration(context, MediaStore.VOLUME_EXTERNAL_PRIMARY) }.getOrNull()
    }

    override fun currentVersion(): String = MediaStore.getVersion(context)

    override suspend fun readChanged(
        sinceGeneration: Long,
        batchSize: Int,
        onBatch: suspend (batch: List<MediaEntity>, totalRows: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val cursor = context.contentResolver.query(
            filesUri,
            MediaRowReader.PROJECTION,
            "$MEDIA_TYPE_CLAUSE AND ${MediaColumns.GENERATION_MODIFIED} > ?",
            arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString(), FileColumns.MEDIA_TYPE_VIDEO.toString(), sinceGeneration.toString()),
            "${MediaColumns.DATE_ADDED} DESC",
        ) ?: throw IllegalStateException("MediaStore returned no cursor")
        cursor.use { readBatches(it, batchSize, onBatch) }
    }

    override suspend fun readAllIds(): List<Long> = withContext(Dispatchers.IO) {
        val cursor = context.contentResolver.query(
            filesUri,
            arrayOf(MediaColumns._ID),
            MEDIA_TYPE_CLAUSE,
            arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString(), FileColumns.MEDIA_TYPE_VIDEO.toString()),
            null,
        ) ?: throw IllegalStateException("MediaStore returned no cursor")
        cursor.use {
            val ids = ArrayList<Long>(it.count)
            while (it.moveToNext()) ids += it.getLong(0)
            ids
        }
    }

    private suspend fun readBatches(
        cursor: Cursor,
        batchSize: Int,
        onBatch: suspend (List<MediaEntity>, Int) -> Unit,
    ) {
        val columns = MediaRowReader(cursor)
        val total = cursor.count
        var batch = ArrayList<MediaEntity>(batchSize)
        while (cursor.moveToNext()) {
            batch += columns.read(cursor)
            if (batch.size < batchSize) continue
            onBatch(batch, total)
            batch = ArrayList(batchSize)
            currentCoroutineContext().ensureActive()
        }
        if (batch.isNotEmpty()) onBatch(batch, total)
    }

    private companion object {
        const val MEDIA_TYPE_CLAUSE = "${FileColumns.MEDIA_TYPE} IN (?, ?)"

    }
}
