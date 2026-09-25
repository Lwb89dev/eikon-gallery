package app.eikon.gallery.data.sync

import androidx.room.withTransaction
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.db.IndexDao
import app.eikon.gallery.data.db.MediaDao
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.db.MediaSearchEntity
import app.eikon.gallery.domain.search.SearchText
import javax.inject.Inject

/**
 * [MediaIndex] over Room. Besides the media rows it keeps the file-name part of the full-text index in
 * step (new rows get an entry, renamed files are updated), and removes everything derived from a photo
 * when the photo goes away.
 */
class RoomMediaIndex @Inject constructor(
    private val database: EikonDatabase,
    private val dao: MediaDao,
    private val index: IndexDao,
) : MediaIndex {
    override suspend fun upsertAll(items: List<MediaEntity>) {
        database.withTransaction {
            dao.upsertAll(items)
            syncFilenames(items)
        }
    }

    override suspend fun allIds(): List<Long> = dao.allIds()

    override suspend fun deleteByIds(ids: List<Long>) {
        database.withTransaction {
            ids.chunked(CHUNK).forEach {
                dao.deleteByIds(it)
                index.deleteStates(it)
                index.deleteGeo(it)
                index.deleteSearch(it)
            }
        }
    }

    /**
     * Empties the media cache and everything derived from it (places, recognized text), so revoking photo
     * access also removes what eikon learned about the photos. Albums and the hidden list are user data
     * and are kept.
     */
    override suspend fun clear() {
        database.withTransaction {
            dao.deleteAll()
            index.clearStates()
            index.clearGeo()
            index.clearSearch()
        }
    }

    private suspend fun syncFilenames(items: List<MediaEntity>) {
        val existing = items.map { it.id }.chunked(CHUNK).flatMap { index.existingSearchRows(it) }.toSet()
        val missing = ArrayList<MediaSearchEntity>()
        for (item in items) {
            val name = SearchText.filename(item.displayName)
            if (item.id in existing) index.setFilename(item.id, name) else missing += MediaSearchEntity(item.id, name, "")
        }
        if (missing.isNotEmpty()) index.insertSearch(missing)
    }

    private companion object {
        /** Below SQLite's bound-variable limit of 999. */
        const val CHUNK = 500
    }
}
