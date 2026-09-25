package app.eikon.gallery.data.db

import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.search.PersonMatch
import app.eikon.gallery.domain.search.SearchSpec
import app.eikon.gallery.domain.search.SearchTerm
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The People queries and the person parts of the library query, run on a real SQLite. */
class PeopleSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaFiles.newest().forEach { execute(it) }
        (1L..6L).forEach { execute("INSERT INTO media (id, displayName, mimeType, isVideo, takenAt, addedAt, modifiedAt, width, height, durationMs, sizeBytes, relativePath, isFavorite, isScreenshot, isScreenRecording, isPanorama, isRaw) VALUES ($it, 'f$it', 'image/jpeg', 0, ${it * 1000}, $it, ${it * 10}, 0, 0, 0, 0, 'DCIM/', 0, 0, 0, 0, 0)") }
        // Marco (person 1): photos 1, 2, 3 (3 is hidden). Giulia (person 2): photos 3, 4. Unnamed (person 3): photo 5. Photo 6 has no faces.
        execute("INSERT INTO person (id, name, isFavorite, isHidden, isPinned, createdAt) VALUES (1, 'Marco', 0, 0, 0, 0)")
        execute("INSERT INTO person (id, name, isFavorite, isHidden, isPinned, createdAt) VALUES (2, 'Giulia', 1, 0, 0, 0)")
        execute("INSERT INTO person (id, name, isFavorite, isHidden, isPinned, createdAt) VALUES (3, NULL, 0, 1, 0, 0)")
        execute("INSERT INTO person (id, name, isFavorite, isHidden, isPinned, createdAt) VALUES (4, 'Nobody', 0, 0, 0, 0)")
        face(1, 1, person = 1, size = 0.20f, score = 0.90f)
        face(2, 2, person = 1, size = 0.40f, score = 0.95f) // the biggest and surest face of Marco: his cover
        face(3, 3, person = 1, size = 0.60f, score = 0.99f) // biggest of all, but in a hidden photo
        face(4, 3, person = 2, size = 0.30f, score = 0.90f)
        face(5, 4, person = 2, size = 0.10f, score = 0.90f)
        face(6, 5, person = 3, size = 0.30f, score = 0.90f)
        execute("INSERT INTO hidden_media (mediaId, hiddenAt) VALUES (3, 0)")
    }

    @After
    fun close() = db.close()

    private fun face(id: Long, media: Long, person: Long?, size: Float, score: Float, ignored: Boolean = false) {
        val personSql = person?.toString() ?: "NULL"
        execute("INSERT INTO face (id, mediaId, `left`, top, `right`, bottom, score, vector, personId, ignored) VALUES ($id, $media, 0.1, 0.1, ${0.1f + size}, ${0.1f + size}, $score, x'00', $personSql, ${if (ignored) 1 else 0})")
    }

    private fun execute(sql: String) = db.createStatement().use { it.execute(sql) }

    private class Row(val id: Long, val name: String?, val favorite: Boolean, val hidden: Boolean, val photos: Int, val coverMedia: Long)

    private fun people(): List<Row> = db.createStatement().use { st ->
        st.executeQuery(PeopleQueries.OBSERVE_PEOPLE).use { rs ->
            generateSequence {
                if (rs.next()) Row(rs.getLong("id"), rs.getString("name"), rs.getInt("isFavorite") == 1, rs.getInt("isHidden") == 1, rs.getInt("photoCount"), rs.getLong("coverMediaId")) else null
            }.toList()
        }
    }

    private fun photos(scope: LibraryScope): List<Long> {
        val sql = LibraryQueryBuilder.media(LibraryQuery(scope = scope))
        return db.prepareStatement(sql.sql).use { st ->
            sql.args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong("id") else null }.toList() }
        }
    }

    @Test
    fun theListHasNamedOrRecurringPeopleFavoritesFirstThenByCount() {
        val list = people()
        // Giulia is a favorite (photo 3 is hidden, so only photo 4 counts); Marco has photos 1 and 2.
        // Person 3 is unnamed, in a single photo and was not made by the user: a passer-by, not listed.
        assertEquals(listOf(2L, 1L), list.map { it.id })
        assertEquals(listOf(1, 2), list.map { it.photos })
    }

    @Test
    fun aSinglePhotoGroupIsListedOnceTheUserNamedPinnedOrFavoritedIt() {
        execute("UPDATE person SET isPinned = 1 WHERE id = 3")
        assertEquals(listOf(2L, 1L, 3L), people().map { it.id })
        execute("UPDATE person SET isPinned = 0, name = 'Ana' WHERE id = 3")
        assertEquals(true, people().any { it.id == 3L })
    }

    @Test
    fun aGroupOfTwoPhotosIsListedWithoutAName() {
        face(7, 6, person = 3, size = 0.3f, score = 0.9f)
        assertEquals(true, people().any { it.id == 3L })
    }

    @Test
    fun aPersonWithNoFacesAtAllIsNotListed() {
        assertEquals(false, people().any { it.id == 4L })
    }

    @Test
    fun theCoverIsTheBiggestSureFaceInAVisiblePhoto() {
        val marco = people().single { it.id == 1L }
        assertEquals(2L, marco.coverMedia) // not photo 3, whose bigger face is in a hidden photo
    }

    @Test
    fun ignoredFacesDoNotCountAndTheirPhotosLeaveThePerson() {
        execute("UPDATE face SET ignored = 1 WHERE id = 2")
        val marco = people().single { it.id == 1L }
        assertEquals(1, marco.photos)
        assertEquals(1L, marco.coverMedia)
        assertEquals(listOf(1L), photos(LibraryScope.Person(1)))
    }

    @Test
    fun aPersonsPhotosAreTheVisiblePhotosTheyAreIn() {
        assertEquals(setOf(1L, 2L), photos(LibraryScope.Person(1)).toSet()) // photo 3 is hidden
        assertEquals(setOf(4L), photos(LibraryScope.Person(2)).toSet())
        assertEquals(emptyList<Long>(), photos(LibraryScope.Person(99)))
    }

    @Test
    fun aNameInASearchFindsThePersonsPhotos() {
        val spec = SearchSpec(listOf(SearchTerm("marco", person = PersonMatch(setOf(1L)))))
        assertEquals(setOf(1L, 2L), photos(LibraryScope.Search(spec)).toSet())
    }

    @Test
    fun aNameSharedByTwoPeopleFindsBoth() {
        val spec = SearchSpec(listOf(SearchTerm("m", person = PersonMatch(setOf(1L, 2L)))))
        assertEquals(setOf(1L, 2L, 4L), photos(LibraryScope.Search(spec)).toSet())
    }

    @Test
    fun aPersonTermAndAWordCombine() {
        // Marco's photos whose text also says "f2": only photo 2.
        execute("INSERT INTO media_search (rowid, filename, ocr) VALUES (1, 'a', ''), (2, 'f2', ''), (4, 'f4', '')")
        val spec = SearchSpec(listOf(SearchTerm("marco", person = PersonMatch(setOf(1L))), SearchTerm("f2")))
        assertEquals(listOf(2L), photos(LibraryScope.Search(spec)))
    }

    @Test
    fun aPersonSearchNeverExposesHiddenPhotos() {
        val spec = SearchSpec(listOf(SearchTerm("giulia", person = PersonMatch(setOf(2L)))))
        assertEquals(listOf(4L), photos(LibraryScope.Search(spec))) // photo 3 has her face but is hidden
    }

    @Test
    fun deletingAPersonsPhotosLeavesNoOrphanRowsForTheListToTripOn() {
        execute("UPDATE person SET isPinned = 1 WHERE id = 3")
        execute("DELETE FROM face WHERE mediaId = 5")
        execute("DELETE FROM person WHERE name IS NULL AND id NOT IN (SELECT personId FROM face WHERE personId IS NOT NULL AND ignored = 0)")
        assertNull(people().firstOrNull { it.id == 3L })
    }
}
