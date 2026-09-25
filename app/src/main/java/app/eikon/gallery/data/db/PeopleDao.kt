package app.eikon.gallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** SQL kept as a constant so tests can run exactly this text against a real SQLite. */
object PeopleQueries {
    const val OBSERVE_PEOPLE = """
        SELECT p.id AS id, p.name AS name, p.isFavorite AS isFavorite, p.isHidden AS isHidden,
               s.photoCount AS photoCount, cf.mediaId AS coverMediaId, cm.modifiedAt AS coverModifiedAt,
               cf.`left` AS coverLeft, cf.top AS coverTop, cf.`right` AS coverRight, cf.bottom AS coverBottom
        FROM person p
        JOIN (
            SELECT f.personId AS personId, COUNT(DISTINCT f.mediaId) AS photoCount
            FROM face f JOIN media m ON m.id = f.mediaId
            WHERE f.ignored = 0 AND f.personId IS NOT NULL AND m.id NOT IN (SELECT mediaId FROM hidden_media)
            GROUP BY f.personId
        ) s ON s.personId = p.id
        JOIN face cf ON cf.id = (
            SELECT f2.id FROM face f2 JOIN media m2 ON m2.id = f2.mediaId
            WHERE f2.personId = p.id AND f2.ignored = 0 AND m2.id NOT IN (SELECT mediaId FROM hidden_media)
            ORDER BY (f2.`right` - f2.`left`) * (f2.bottom - f2.top) * f2.score DESC, f2.id LIMIT 1
        )
        JOIN media cm ON cm.id = cf.mediaId
        WHERE s.photoCount >= 2 OR p.name IS NOT NULL OR p.isFavorite = 1 OR p.isPinned = 1
        ORDER BY p.isFavorite DESC, s.photoCount DESC, p.id
        """
}

@Dao
interface PeopleDao {
    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    @Insert
    suspend fun insertFaces(faces: List<FaceEntity>)

    /**
     * Everyone with at least one visible photo, favorites first, then by how many photos they are in. Photos the
     * user has hidden count for nothing, so hiding a photo also removes it from a person's count and picture.
     */
    @Query(PeopleQueries.OBSERVE_PEOPLE)
    fun observePeople(): Flow<List<PersonSummary>>

    @Query("SELECT id, name FROM person WHERE name IS NOT NULL AND isHidden = 0")
    suspend fun namedPeople(): List<PersonName>

    @Query("SELECT * FROM person WHERE id = :id")
    fun observePerson(id: Long): Flow<PersonEntity?>

    @Query("SELECT * FROM person WHERE id = :id")
    suspend fun personOrNull(id: Long): PersonEntity?

    @Query("SELECT f.id AS faceId, f.personId AS personId, p.name AS name FROM face f LEFT JOIN person p ON p.id = f.personId WHERE f.mediaId = :mediaId AND f.ignored = 0 ORDER BY f.`left`")
    suspend fun facesOfPhoto(mediaId: Long): List<FaceOfPhoto>

    // --- what the user can do to a person --------------------------------------------------------

    @Query("UPDATE person SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String?)

    @Query("UPDATE person SET isFavorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE person SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    /** Merging: every face of [from] becomes a face of [into]. The emptied person is deleted separately. */
    @Query("UPDATE face SET personId = :into WHERE personId = :from")
    suspend fun moveFaces(from: Long, into: Long)

    /** Splitting: the faces of [from] that are in these photos move to [into]. */
    @Query("UPDATE face SET personId = :into WHERE personId = :from AND mediaId IN (:mediaIds)")
    suspend fun moveFacesInPhotos(from: Long, into: Long, mediaIds: List<Long>)

    @Query("UPDATE face SET ignored = 1 WHERE personId = :personId AND mediaId IN (:mediaIds)")
    suspend fun ignoreFacesInPhotos(personId: Long, mediaIds: List<Long>)

    @Query("DELETE FROM person WHERE id = :id")
    suspend fun deletePerson(id: Long)

    /** People nobody named and no (non-ignored) face belongs to any more. Named people are kept: the user made them. */
    @Query("DELETE FROM person WHERE name IS NULL AND id NOT IN (SELECT personId FROM face WHERE personId IS NOT NULL AND ignored = 0)")
    suspend fun deleteEmptyUnnamedPeople()

    // --- analysis ------------------------------------------------------------------------------------

    @Query("DELETE FROM face WHERE mediaId = :mediaId")
    suspend fun deleteFacesOfPhoto(mediaId: Long)

    @Query("SELECT id, personId, vector FROM face WHERE personId IS NOT NULL AND ignored = 0 AND id > :after ORDER BY id LIMIT :limit")
    suspend fun assignedVectors(after: Long, limit: Int): List<AssignedVector>

    // --- cleanup when media disappears --------------------------------------------------------------

    @Query("DELETE FROM face WHERE mediaId IN (:ids)")
    suspend fun deleteFaces(ids: List<Long>)

    @Query("DELETE FROM face")
    suspend fun clearFaces()

    @Query("DELETE FROM person")
    suspend fun clearPeople()
}
