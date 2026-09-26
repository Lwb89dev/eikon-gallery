package app.eikon.gallery.data.sync

import androidx.room.withTransaction
import app.eikon.gallery.data.db.BackupDao
import app.eikon.gallery.data.db.DuplicatesDao
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.db.IndexDao
import app.eikon.gallery.data.db.MediaDao
import app.eikon.gallery.data.db.MediaEntity
import app.eikon.gallery.data.db.MediaSearchEntity
import app.eikon.gallery.data.db.PeopleDao
import app.eikon.gallery.domain.search.SearchText
import javax.inject.Inject

/**
 * [MediaIndex] over Room. Besides the media rows it keeps the file-name part of the full-text index in
 * step (new rows get an entry, renamed files are updated), and removes everything derived from a photo
 * when the photo goes away or its file is rewritten (see [FileChange]).
 */
class RoomMediaIndex @Inject constructor(
    private val database: EikonDatabase,
    private val dao: MediaDao,
    private val index: IndexDao,
    private val people: PeopleDao,
    private val duplicates: DuplicatesDao,
    private val backup: BackupDao,
) : MediaIndex {
    override suspend fun upsertAll(items: List<MediaEntity>) {
        database.withTransaction {
            val rewritten = rewrittenFiles(items)
            dao.upsertAll(items)
            forgetAnalysis(rewritten)
            syncFilenames(items)
        }
    }

    /** Photos already in the index whose file has been rewritten since (an edit in another app, a change of location): what was learned from the old file no longer describes it. */
    private suspend fun rewrittenFiles(items: List<MediaEntity>): List<Long> {
        val before = items.map { it.id }.chunked(CHUNK).flatMap { dao.byIds(it) }.associateBy { it.id }
        return items.filter { item -> before[item.id]?.let { FileChange.rewritten(it, item) } == true }.map { it.id }
    }

    /** Everything the analysis learned about these photos, so it is learned again from the file as it is now. Fingerprints for duplicates check the modification time themselves. */
    private suspend fun forgetAnalysis(ids: List<Long>) {
        if (ids.isEmpty()) return
        ids.chunked(CHUNK).forEach {
            index.deleteStates(it)
            index.deleteGeo(it)
            index.deleteSearch(it)
            index.deleteEmbeddings(it)
            people.deleteFaces(it)
        }
        people.deleteEmptyUnnamedPeople()
    }

    override suspend fun allIds(): List<Long> = dao.allIds()

    override suspend fun deleteByIds(ids: List<Long>) {
        database.withTransaction {
            ids.chunked(CHUNK).forEach {
                dao.deleteByIds(it)
                duplicates.deleteContentHashes(it)
                duplicates.deletePerceptualHashes(it)
            }
            forgetAnalysis(ids)
        }
    }

    /**
     * Empties the media cache and everything derived from it (places, recognized text, image embeddings, faces and people, file fingerprints, what was sent to a backup server), so revoking photo
     * access also removes what eikon learned about the photos. Albums and the hidden list are user data
     * and are kept.
     */
    override suspend fun clear() {
        database.withTransaction {
            dao.deleteAll()
            index.clearStates()
            index.clearGeo()
            index.clearSearch()
            index.clearEmbeddings()
            index.clearHits()
            people.clearFaces()
            people.clearPeople()
            duplicates.clearContentHashes()
            duplicates.clearPerceptualHashes()
            backup.clear()
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
