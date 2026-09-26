package app.eikon.gallery.data.mediastore

import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import android.provider.MediaStore.MediaColumns
import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.toDomain
import app.eikon.gallery.domain.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A photo or video in the system trash and when Android will delete it for good. */
data class TrashedMedia(val item: MediaItem, val expiresAtMillis: Long)

/** How many whole days are left, rounding up, never negative. */
object TrashPolicy {
    private const val MILLIS_PER_DAY = 86_400_000L

    fun daysLeft(expiresAtMillis: Long, nowMillis: Long): Int {
        val remaining = expiresAtMillis - nowMillis
        if (remaining <= 0) return 0
        return ((remaining + MILLIS_PER_DAY - 1) / MILLIS_PER_DAY).toInt()
    }
}

/**
 * The "Recently deleted" data. Android's own MediaStore trash is the storage (items stay put for
 * about 30 days), so nothing is duplicated in eikon's database: this reads it live and restores or
 * deletes through the platform's confirmation dialogs.
 */
@Singleton
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** How many photos and videos are in the trash, without reading anything about them. */
    suspend fun count(): Int = withContext(Dispatchers.IO) {
        resolver.query(filesUri, arrayOf(MediaColumns._ID), trashedOnly(), null)?.use { it.count } ?: 0
    }

    /** The query arguments that ask for the trashed photos and videos only, the ones expiring first coming first. */
    private fun trashedOnly() = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${FileColumns.MEDIA_TYPE} IN (?, ?)")
        putStringArray(
            ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
            arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString(), FileColumns.MEDIA_TYPE_VIDEO.toString()),
        )
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaColumns.DATE_EXPIRES))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_ASCENDING)
    }

    private val filesUri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    suspend fun load(): List<TrashedMedia> = withContext(Dispatchers.IO) {
        val projection = MediaRowReader.PROJECTION + MediaColumns.DATE_EXPIRES
        val cursor = resolver.query(filesUri, projection, trashedOnly(), null)
            ?: return@withContext emptyList()
        cursor.use {
            val reader = MediaRowReader(it)
            val expires = it.getColumnIndexOrThrow(MediaColumns.DATE_EXPIRES)
            val result = ArrayList<TrashedMedia>(it.count)
            while (it.moveToNext()) {
                result += TrashedMedia(reader.read(it).toDomain(), it.getLong(expires) * MILLIS_PER_SECOND)
            }
            result
        }
    }

    fun daysLeft(media: TrashedMedia): Int = TrashPolicy.daysLeft(media.expiresAtMillis, clock.nowMillis())

    fun restoreRequest(items: Collection<MediaItem>): IntentSender =
        MediaStore.createTrashRequest(resolver, items.map { it.uri }, false).intentSender

    fun deleteForeverRequest(items: Collection<MediaItem>): IntentSender =
        MediaStore.createDeleteRequest(resolver, items.map { it.uri }).intentSender

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}
