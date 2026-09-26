package app.eikon.gallery.data.duplicates

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.DuplicateDismissalEntity
import app.eikon.gallery.data.db.DuplicatesDao
import app.eikon.gallery.data.db.toDomain
import app.eikon.gallery.data.embedding.EmbeddingRepository
import app.eikon.gallery.domain.MediaItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which of the two lists a screen shows. */
enum class DuplicateMode { DUPLICATES, SIMILAR }

/**
 * One group to show: its [items] (best first for duplicates, oldest first for similar shots), and, for duplicates, how the copies
 * relate. [bestId] is the suggestion of which to keep; there is none for similar shots, because which of several
 * near-identical shots is best is a taste, not a measurement.
 */
class DuplicateEntry(val key: String, val kind: DuplicateKind?, val items: List<MediaItem>, val bestId: Long?)

/** Duplicates and similar shots, worked out from the stored fingerprints. Nothing here changes or deletes a file. */
@Singleton
class DuplicatesRepository @Inject constructor(
    private val dao: DuplicatesDao,
    private val embeddings: EmbeddingRepository,
    private val clock: Clock,
) {
    suspend fun duplicates(): List<DuplicateEntry> = withContext(Dispatchers.Default) {
        val dismissed = dao.dismissedKeys().toSet()
        entries(findDuplicates().filter { it.key !in dismissed })
    }

    suspend fun similar(): List<DuplicateEntry> = withContext(Dispatchers.Default) {
        val dismissed = dao.dismissedKeys().toSet()
        val matrix = embeddings.matrix()
        if (matrix.size == 0) return@withContext emptyList()
        val times = dao.photoTimes().associate { it.id to it.takenAt }
        val duplicateSets = findDuplicates().map { group -> group.members.map { it.id }.toSet() }
        val groups = SimilarShotFinder.find(matrix, times)
            .filter { it.key !in dismissed }
            .filterNot { similar -> duplicateSets.any { it.containsAll(similar.members) } }
        val byId = loadItems(groups.flatMap { it.members }.distinct())
        groups.mapNotNull { group ->
            val items = group.members.mapNotNull { byId[it] }.sortedBy { it.takenAt }
            if (items.size < 2) null else DuplicateEntry(group.key, null, items, null)
        }
    }

    /** Remembers that the user said this group is not one, so it is not offered again. */
    suspend fun dismiss(key: String) = dao.dismiss(DuplicateDismissalEntity(key, clock.nowMillis()))

    /** The albums any of these items is in, so the copy that is kept can join them. */
    suspend fun albumsOf(ids: List<Long>): List<Long> = ids.chunked(CHUNK).flatMap { dao.albumsOf(it) }.distinct()

    private suspend fun findDuplicates(): List<DuplicateGroup> = DuplicateFinder.find(dao.candidates())

    private suspend fun entries(groups: List<DuplicateGroup>): List<DuplicateEntry> {
        val byId = loadItems(groups.flatMap { group -> group.members.map { it.id } }.distinct())
        return groups.mapNotNull { group ->
            val items = group.members.mapNotNull { byId[it.id] }
            if (items.size < 2) null else DuplicateEntry(group.key, group.kind, items, items.first().id)
        }
    }

    private suspend fun loadItems(ids: List<Long>): Map<Long, MediaItem> =
        ids.chunked(CHUNK).flatMap { dao.media(it) }.associate { it.id to it.toDomain() }

    private companion object {
        /** Below SQLite's bound-variable limit of 999. */
        const val CHUNK = 500
    }
}
