package app.eikon.gallery.data.faces

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.AssignedVector
import app.eikon.gallery.data.db.FaceEntity
import app.eikon.gallery.data.db.FaceOfPhoto
import app.eikon.gallery.data.db.PeopleDao
import app.eikon.gallery.data.db.PersonEntity
import app.eikon.gallery.data.db.PersonName
import app.eikon.gallery.data.db.PersonSummary
import app.eikon.gallery.data.db.Transactor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Grouping and editing of people, on an in-memory stand-in for the database. */
class PeopleRepositoryTest {
    private val dao = FakePeopleDao()
    private val repository = PeopleRepository(Transactor(), dao, Clock { 42L })

    private fun Transactor() = object : Transactor {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    private fun unit(vararg values: Float): FloatArray {
        val norm = kotlin.math.sqrt(values.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(values.size) { values[it] / norm }
    }

    private fun face(vararg values: Float) = NewFace(0.1f, 0.1f, 0.3f, 0.3f, 0.9f, unit(*values))

    private fun personOf(mediaId: Long): Long? = dao.faces.single { it.mediaId == mediaId }.personId

    @Test
    fun aFaceNobodyResemblesStartsANewPersonAndALookAlikeJoinsThem() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f, 0f)))
        repository.saveFaces(2, listOf(face(0.95f, 0.1f, 0f)))
        repository.saveFaces(3, listOf(face(0f, 1f, 0f)))

        assertEquals(personOf(1), personOf(2))
        assertNotEquals(personOf(1), personOf(3))
        assertEquals(2, dao.people.size)
    }

    @Test
    fun twoFacesInTheSamePhotoThatLookAlikeAreOnePersonNotTwo() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f), face(0.99f, 0.05f)))
        assertEquals(1, dao.people.size)
    }

    @Test
    fun savingAPhotoAgainReplacesItsFacesInsteadOfDuplicatingThem() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f)))
        repository.saveFaces(1, listOf(face(1f, 0f)))
        assertEquals(1, dao.faces.count { it.mediaId == 1L })
        assertEquals(1, dao.people.size)
    }

    @Test
    fun aPhotoWhoseFacesChangedNoLongerCountsTowardsThePersonItWasIn() = runTest {
        // Photo 1 was first analysed as showing one person; edited since, it now shows someone quite different.
        repository.saveFaces(1, listOf(face(1f, 0f, 0f)))
        repository.saveFaces(1, listOf(face(0f, 1f, 0f)))

        // A face like the first one must not be pulled to the person who is now in photo 1 by what photo 1 used to show.
        repository.saveFaces(2, listOf(face(1f, 0f, 0f)))

        assertNotEquals(personOf(1), personOf(2))
        assertEquals("the person only the old picture showed is gone with it", 2, dao.people.size)
    }

    @Test
    fun aPhotoWithoutFacesStoresNothing() = runTest {
        repository.saveFaces(1, emptyList())
        assertEquals(0, dao.faces.size)
    }

    @Test
    fun groupingContinuesFromWhatIsStoredAfterARestart() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f, 0f)))
        val restarted = PeopleRepository(Transactor(), dao, Clock { 43L }) // a new process: nothing in memory

        restarted.saveFaces(2, listOf(face(0.97f, 0.05f, 0f)))

        assertEquals(personOf(1), personOf(2))
        assertEquals(1, dao.people.size)
    }

    @Test
    fun mergingMovesEveryFaceAndKeepsTheTargetsNameOrTakesTheSourcesIfItHasNone() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f)))
        repository.saveFaces(2, listOf(face(0f, 1f)))
        val (a, b) = listOf(personOf(1)!!, personOf(2)!!)
        repository.rename(a, "Marco")

        repository.merge(from = a, into = b) // b has no name: takes Marco

        assertEquals(setOf(b), dao.faces.map { it.personId }.toSet())
        assertEquals("Marco", dao.people.getValue(b).name)
        assertNull(dao.people[a])
    }

    @Test
    fun mergingKeepsTheTargetsOwnNameAndFavorite() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f)))
        repository.saveFaces(2, listOf(face(0f, 1f)))
        val (a, b) = listOf(personOf(1)!!, personOf(2)!!)
        repository.rename(a, "Old")
        repository.rename(b, "New")
        repository.setFavorite(a, true)

        repository.merge(from = a, into = b)

        assertEquals("New", dao.people.getValue(b).name)
        assertEquals(true, dao.people.getValue(b).isFavorite)
    }

    @Test
    fun aFaceThatLooksLikeAMergedPersonJoinsThemAfterwards() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f, 0f)))
        repository.saveFaces(2, listOf(face(0f, 1f, 0f)))
        val target = personOf(2)!!
        repository.merge(from = personOf(1)!!, into = target)

        repository.saveFaces(3, listOf(face(0.98f, 0.05f, 0f))) // resembles what used to be the first person

        assertEquals(target, personOf(3))
    }

    @Test
    fun mergingSomeoneWithThemselvesDoesNothing() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f)))
        val id = personOf(1)!!
        repository.merge(id, id)
        assertEquals(id, personOf(1))
        assertEquals(1, dao.people.size)
    }

    @Test
    fun splittingMovesThosePhotosFacesToANewPersonAndKeepsTheOthers() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f)))
        repository.saveFaces(2, listOf(face(0.99f, 0.05f)))
        repository.saveFaces(3, listOf(face(0.98f, 0.1f)))
        val original = personOf(1)!!

        val created = repository.split(original, listOf(2, 3))

        assertNotEquals(original, created)
        assertEquals(original, personOf(1))
        assertEquals(created, personOf(2))
        assertEquals(created, personOf(3))
    }

    @Test
    fun aSplitPersonIsPinnedSoItIsListedEvenWithASinglePhoto() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f)))
        repository.saveFaces(2, listOf(face(0.99f, 0.05f)))
        val created = repository.split(personOf(1)!!, listOf(2))
        assertEquals(true, dao.people.getValue(created).isPinned)
    }

    @Test
    fun splitFacesAreNotPulledBackIntoTheOldPersonByLaterAnalysis() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f)))
        repository.saveFaces(2, listOf(face(0.99f, 0.05f)))
        val created = repository.split(personOf(1)!!, listOf(2))

        repository.saveFaces(3, listOf(face(0.5f, 0.8f)))

        // The new person is recomputed from what is stored, so later faces are compared with the real groups.
        assertNotEquals(null, personOf(3))
        assertEquals(created, personOf(2))
    }

    @Test
    fun ignoredFacesLeaveThePersonAndAnEmptyUnnamedPersonIsRemoved() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f)))
        val id = personOf(1)!!

        repository.ignoreFaces(id, listOf(1))

        assertEquals(true, dao.faces.single().ignored)
        assertNull(dao.people[id])
    }

    @Test
    fun aNamedPersonSurvivesLosingAllTheirFaces() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f)))
        val id = personOf(1)!!
        repository.rename(id, "Marco")
        repository.ignoreFaces(id, listOf(1))
        assertEquals("Marco", dao.people.getValue(id).name)
    }

    @Test
    fun aBlankNameClearsTheName() = runTest {
        repository.saveFaces(1, listOf(face(1f, 0f)))
        val id = personOf(1)!!
        repository.rename(id, "  Marco ")
        assertEquals("Marco", dao.people.getValue(id).name)
        repository.rename(id, "   ")
        assertNull(dao.people.getValue(id).name)
    }

    // --- in-memory database --------------------------------------------------------------------------

    private class FakePeopleDao : PeopleDao {
        val people = LinkedHashMap<Long, PersonEntity>()
        val faces = ArrayList<FaceEntity>()
        private var nextPerson = 1L
        private var nextFace = 1L

        override suspend fun insertPerson(person: PersonEntity): Long {
            val id = nextPerson++
            people[id] = person.copy(id = id)
            return id
        }

        override suspend fun insertFaces(faces: List<FaceEntity>) {
            faces.forEach {
                this.faces += FaceEntity(nextFace++, it.mediaId, it.left, it.top, it.right, it.bottom, it.score, it.vector, it.personId, it.ignored)
            }
        }

        override fun observePeople(): Flow<List<PersonSummary>> = flowOf(emptyList())
        override suspend fun namedPeople(): List<PersonName> = people.values.filter { it.name != null && !it.isHidden }.map { PersonName(it.id, it.name!!) }
        override fun observePerson(id: Long): Flow<PersonEntity?> = flowOf(people[id])
        override suspend fun personOrNull(id: Long): PersonEntity? = people[id]
        override suspend fun facesOfPhoto(mediaId: Long): List<FaceOfPhoto> =
            faces.filter { it.mediaId == mediaId && !it.ignored }.map { FaceOfPhoto(it.id, it.personId, people[it.personId]?.name) }

        override suspend fun rename(id: Long, name: String?) = update(id) { it.copy(name = name) }
        override suspend fun setFavorite(id: Long, favorite: Boolean) = update(id) { it.copy(isFavorite = favorite) }
        override suspend fun setHidden(id: Long, hidden: Boolean) = update(id) { it.copy(isHidden = hidden) }

        private fun update(id: Long, change: (PersonEntity) -> PersonEntity) {
            people[id]?.let { people[id] = change(it) }
        }

        override suspend fun moveFaces(from: Long, into: Long) = retarget { it.personId == from }.forEach { set(it, into) }

        override suspend fun moveFacesInPhotos(from: Long, into: Long, mediaIds: List<Long>) =
            retarget { it.personId == from && it.mediaId in mediaIds }.forEach { set(it, into) }

        override suspend fun ignoreFacesInPhotos(personId: Long, mediaIds: List<Long>) {
            faces.replaceAll { if (it.personId == personId && it.mediaId in mediaIds) copyOf(it, it.personId, ignored = true) else it }
        }

        override suspend fun deletePerson(id: Long) {
            people.remove(id)
        }

        override suspend fun deleteEmptyUnnamedPeople() {
            val used = faces.filter { !it.ignored }.mapNotNull { it.personId }.toSet()
            people.keys.toList().forEach { if (people.getValue(it).name == null && it !in used) people.remove(it) }
        }

        override suspend fun deleteFacesOfPhoto(mediaId: Long): Int {
            val before = faces.size
            faces.removeAll { it.mediaId == mediaId }
            return before - faces.size
        }

        override suspend fun assignedVectors(after: Long, limit: Int): List<AssignedVector> =
            faces.filter { it.personId != null && !it.ignored && it.id > after }.sortedBy { it.id }.take(limit)
                .map { AssignedVector(it.id, it.personId!!, it.vector) }

        override suspend fun deleteFaces(ids: List<Long>) {
            faces.removeAll { it.mediaId in ids }
        }

        override suspend fun clearFaces() = faces.clear()
        override suspend fun clearPeople() = people.clear()

        private fun retarget(filter: (FaceEntity) -> Boolean): List<FaceEntity> = faces.filter(filter)

        private fun set(face: FaceEntity, person: Long) {
            faces[faces.indexOf(face)] = copyOf(face, person, face.ignored)
        }

        private fun copyOf(f: FaceEntity, person: Long?, ignored: Boolean) =
            FaceEntity(f.id, f.mediaId, f.left, f.top, f.right, f.bottom, f.score, f.vector, person, ignored)
    }
}
