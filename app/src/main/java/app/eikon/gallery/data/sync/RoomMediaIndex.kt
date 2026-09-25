package app.eikon.gallery.data.sync

import app.eikon.gallery.data.db.MediaDao
import app.eikon.gallery.data.db.MediaEntity
import javax.inject.Inject

/** [MediaIndex] over the Room DAO. */
class RoomMediaIndex @Inject constructor(
    private val dao: MediaDao,
) : MediaIndex {
    override suspend fun upsertAll(items: List<MediaEntity>) = dao.upsertAll(items)

    override suspend fun allIds(): List<Long> = dao.allIds()

    override suspend fun deleteByIds(ids: List<Long>) = dao.deleteByIds(ids)

    /** Empties the media cache only; albums and the hidden list are user data and are kept. */
    override suspend fun clear() = dao.deleteAll()
}
