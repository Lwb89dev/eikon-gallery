package app.eikon.gallery.data

import app.eikon.gallery.data.db.AlbumDao
import app.eikon.gallery.data.db.AlbumEntity
import app.eikon.gallery.data.db.AlbumItemEntity
import app.eikon.gallery.data.db.AlbumSummary
import app.eikon.gallery.data.db.FolderSummary
import app.eikon.gallery.data.db.HiddenDao
import app.eikon.gallery.data.db.HiddenMediaEntity
import app.eikon.gallery.data.db.MediaDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Albums (virtual, kept in eikon's database) and read-only device folders (real MediaStore
 * locations). Keeping the two apart is deliberate: deleting an album never touches a file, and a
 * folder cannot be renamed or deleted from here.
 */
@Singleton
class AlbumRepository @Inject constructor(
    private val albums: AlbumDao,
    private val media: MediaDao,
    private val hidden: HiddenDao,
    private val clock: Clock,
) {
    val albumSummaries: Flow<List<AlbumSummary>> = albums.observeAlbums()

    val folders: Flow<List<FolderSummary>> = media.observeFolders()

    fun album(id: Long): Flow<AlbumEntity?> = albums.observeAlbum(id)

    /** Creates an album at the end of the list. Returns its id, or null when [name] is blank. */
    suspend fun create(name: String): Long? {
        val clean = AlbumNames.clean(name) ?: return null
        return albums.create(clean, clock.nowMillis())
    }

    suspend fun rename(id: Long, name: String): Boolean {
        val clean = AlbumNames.clean(name) ?: return false
        albums.rename(id, clean)
        return true
    }

    /** Deletes the album only; the photos in it are untouched. */
    suspend fun delete(id: Long) = albums.delete(id)

    suspend fun moveEarlier(id: Long) = albums.move(id, -1)

    suspend fun moveLater(id: Long) = albums.move(id, +1)

    /** Adding a photo that is already in the album is a no-op. */
    suspend fun addToAlbum(albumId: Long, mediaIds: Collection<Long>) {
        val now = clock.nowMillis()
        albums.addItems(mediaIds.map { AlbumItemEntity(albumId, it, now) })
    }

    suspend fun removeFromAlbum(albumId: Long, mediaIds: Collection<Long>) {
        mediaIds.chunked(CHUNK).forEach { albums.removeItems(albumId, it) }
    }

    suspend fun hide(mediaIds: Collection<Long>) {
        val now = clock.nowMillis()
        hidden.hide(mediaIds.map { HiddenMediaEntity(it, now) })
    }

    suspend fun unhide(mediaIds: Collection<Long>) {
        mediaIds.chunked(CHUNK).forEach { hidden.unhide(it) }
    }

    private companion object {
        /** Below SQLite's bound-variable limit of 999. */
        const val CHUNK = 500
    }
}

/** Album name rules, in one testable place. */
object AlbumNames {
    const val MAX_LENGTH = 60

    /** Trimmed name, or null if it is blank. Over-long names are cut rather than rejected. */
    fun clean(raw: String): String? = raw.trim().take(MAX_LENGTH).trim().ifEmpty { null }
}

/** Time source, replaceable in tests. */
fun interface Clock {
    fun nowMillis(): Long
}
