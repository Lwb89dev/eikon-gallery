package app.eikon.gallery.data.faces

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.FaceEntity
import app.eikon.gallery.data.db.FaceOfPhoto
import app.eikon.gallery.data.db.PeopleDao
import app.eikon.gallery.data.db.PersonEntity
import app.eikon.gallery.data.db.PersonName
import app.eikon.gallery.data.db.PersonSummary
import app.eikon.gallery.data.db.Transactor
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * People: storing the faces the analysis finds, grouping them, and everything the user can do with the groups.
 * Grouping only ever places faces that have no person yet, so a name, a merge or a split made by the user is
 * never undone by later analysis.
 */
@Singleton
class PeopleRepository @Inject constructor(
    private val transactor: Transactor,
    private val dao: PeopleDao,
    private val clock: Clock,
) {
    private val clustering = FaceClustering()
    private val clusteringLock = Mutex()
    private var seeded = false

    /** Everyone with at least one visible photo (see [PeopleDao.observePeople]). Hidden people are included, flagged. */
    val people: Flow<List<PersonSummary>> = dao.observePeople()

    fun person(id: Long): Flow<PersonEntity?> = dao.observePerson(id)

    suspend fun namedPeople(): List<PersonName> = dao.namedPeople()

    suspend fun facesOfPhoto(mediaId: Long): List<FaceOfPhoto> = dao.facesOfPhoto(mediaId)

    /**
     * Replaces whatever is stored for [mediaId] with [faces], grouping each with the person it resembles or
     * starting a new person. Safe to repeat for the same photo.
     */
    suspend fun saveFaces(mediaId: Long, faces: List<NewFace>) = clusteringLock.withLock {
        ensureSeeded()
        try {
            val replaced = transactor.run {
                val removed = dao.deleteFacesOfPhoto(mediaId)
                dao.insertFaces(faces.map { face -> entity(mediaId, face, personFor(face.vector)) })
                if (removed > 0) dao.deleteEmptyUnnamedPeople()
                removed > 0
            }
            // The averages in memory still hold the faces that were replaced: build them again from what is stored.
            if (replaced) forgetClustering()
        } catch (e: Exception) {
            forgetClustering() // memory may hold faces the failed transaction did not keep
            throw e
        }
    }

    // --- Editing ---------------------------------------------------------------------------------------

    /** A blank name clears it. */
    suspend fun rename(personId: Long, name: String) = dao.rename(personId, name.trim().ifEmpty { null })

    suspend fun setFavorite(personId: Long, favorite: Boolean) = dao.setFavorite(personId, favorite)

    suspend fun setHidden(personId: Long, hidden: Boolean) = dao.setHidden(personId, hidden)

    /**
     * Makes [from] and [into] one person. Faces move to [into], which keeps its own name and only takes [from]'s
     * if it has none, and stays a favorite if either was.
     */
    suspend fun merge(from: Long, into: Long) {
        if (from == into) return
        clusteringLock.withLock {
            transactor.run {
                val source = dao.personOrNull(from) ?: return@run
                val target = dao.personOrNull(into) ?: return@run
                dao.moveFaces(from, into)
                if (target.name == null && source.name != null) dao.rename(into, source.name)
                if (source.isFavorite && !target.isFavorite) dao.setFavorite(into, true)
                dao.deletePerson(from)
            }
            forgetClustering()
        }
    }

    /** Takes the faces of [personId] found in [mediaIds] out into a new person, who is returned. */
    suspend fun split(personId: Long, mediaIds: List<Long>): Long = clusteringLock.withLock {
        val created = transactor.run {
            val id = dao.insertPerson(PersonEntity(name = null, isPinned = true, createdAt = clock.nowMillis()))
            mediaIds.chunked(CHUNK).forEach { dao.moveFacesInPhotos(personId, id, it) }
            dao.deleteEmptyUnnamedPeople()
            id
        }
        forgetClustering()
        created
    }

    /** "This is not a face" or "leave them out of People": those faces are ignored from now on. */
    suspend fun ignoreFaces(personId: Long, mediaIds: List<Long>) = clusteringLock.withLock {
        transactor.run {
            mediaIds.chunked(CHUNK).forEach { dao.ignoreFacesInPhotos(personId, it) }
            dao.deleteEmptyUnnamedPeople()
        }
        forgetClustering()
    }

    // --- Grouping ----------------------------------------------------------------------------------------

    private suspend fun personFor(vector: FloatArray): Long {
        val existing = clustering.match(vector)
        val id = existing ?: dao.insertPerson(PersonEntity(name = null, createdAt = clock.nowMillis()))
        clustering.add(id, vector)
        return id
    }

    private fun entity(mediaId: Long, face: NewFace, personId: Long) =
        FaceEntity(
            mediaId = mediaId, left = face.left, top = face.top, right = face.right, bottom = face.bottom,
            score = face.score, vector = FaceVectors.toBytes(face.vector), personId = personId,
        )

    /** Rebuilds the in-memory picture of every person from the stored faces, once, on first use. */
    private suspend fun ensureSeeded() {
        if (seeded) return
        var after = 0L
        while (true) {
            val rows = dao.assignedVectors(after, CHUNK)
            if (rows.isEmpty()) break
            rows.forEach { clustering.add(it.personId, FaceVectors.fromBytes(it.vector)) }
            after = rows.last().id
        }
        seeded = true
    }

    private fun forgetClustering() {
        clustering.forget()
        seeded = false
    }

    private companion object {
        const val CHUNK = 500
    }
}
