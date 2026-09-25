package app.eikon.gallery.data.db

import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.SortDirection
import app.eikon.gallery.domain.SortField
import app.eikon.gallery.domain.TimelineGrouping
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * How SQLite will run the library's queries on a big library, asked with EXPLAIN QUERY PLAN on the tables Room exported. The grid pages over
 * `ORDER BY takenAt`, so the order must come from an index and never from sorting a whole table: with 50,000 photos that difference is the
 * difference between the first page appearing at once and a wait. These are plans, not timings; how fast a phone runs them is not measured here.
 */
class LibraryQueryPlanTest {
    private lateinit var db: Connection

    @Before
    fun openDatabase() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaFiles.newest().forEach { db.createStatement().use { s -> s.execute(it) } }
        db.autoCommit = false
        db.prepareStatement(
            "INSERT INTO media (id, displayName, mimeType, isVideo, takenAt, addedAt, modifiedAt, width, height, durationMs, sizeBytes, relativePath, isFavorite, isScreenshot, isScreenRecording, isPanorama, isRaw) " +
                "VALUES (?, 'f', 'image/jpeg', 0, ?, ?, ?, 0, 0, 0, 0, ?, ?, 0, 0, 0, 0)",
        ).use { st ->
            for (id in 1L..ROWS) {
                st.setLong(1, id)
                st.setLong(2, 1_700_000_000_000L + id * 60_000L)
                st.setLong(3, 1_700_000_000_000L + id * 61_000L)
                st.setLong(4, 1_700_000_000_000L + id * 60_000L)
                st.setString(5, if (id % 500 == 0L) "Small/" else "DCIM/Camera/")
                st.setInt(6, if (id % 10 == 0L) 1 else 0)
                st.executeUpdate()
            }
        }
        db.commit()
    }

    @After
    fun close() = db.close()

    private fun plan(query: SqlQuery): String = db.prepareStatement("EXPLAIN QUERY PLAN ${query.sql}").use { st ->
        query.args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
        st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString("detail") else null }.toList() }
    }.joinToString(" | ")

    private fun sorts(plan: String) = "USE TEMP B-TREE FOR ORDER BY" in plan

    private val sorts = listOf(SortField.DATE_TAKEN to "index_media_takenAt", SortField.DATE_ADDED to "index_media_addedAt")

    @Test
    fun theWholeLibraryIsReadInDateOrderStraightFromTheIndex() {
        for ((field, index) in sorts) for (direction in SortDirection.entries) {
            val query = LibraryQuery(sortField = field, direction = direction)
            val plan = plan(LibraryQueryBuilder.media(query, NOW))
            assertTrue("$field $direction: $plan", index in plan && !sorts(plan))
            assertTrue("no full-table sort for the cover: ${plan(LibraryQueryBuilder.cover(query, NOW))}", !sorts(plan(LibraryQueryBuilder.cover(query, NOW))))
        }
    }

    @Test
    fun filtersDoNotBringBackASort() {
        val filters = listOf(LibraryFilters(favoritesOnly = true), LibraryFilters(category = app.eikon.gallery.domain.CategoryFilter.SCREENSHOTS), LibraryFilters(app.eikon.gallery.domain.TypeFilter.PHOTOS))
        for (filter in filters) {
            val plan = plan(LibraryQueryBuilder.media(LibraryQuery(filters = filter), NOW))
            assertTrue("$filter: $plan", !sorts(plan))
        }
    }

    @Test
    fun aDeviceFolderIsReadFromItsOwnIndexNeverByScanningTheLibrary() {
        for ((field, _) in sorts) for (direction in SortDirection.entries) {
            val query = LibraryQuery(LibraryScope.Folder("Small/"), sortField = field, direction = direction)
            val plan = plan(LibraryQueryBuilder.media(query, NOW))
            assertTrue("$field $direction: $plan", "index_media_relativePath_" in plan && "SCAN m" !in plan && !sorts(plan))
        }
    }

    @Test
    fun theDateSectionsAndTheCountAreAnsweredFromTheIndexAlone() {
        for (grouping in TimelineGrouping.entries) {
            val plan = plan(LibraryQueryBuilder.sections(LibraryQuery(), grouping, NOW))
            assertTrue("$grouping: $plan", "COVERING INDEX index_media_takenAt" in plan)
        }
        val folder = plan(LibraryQueryBuilder.sections(LibraryQuery(LibraryScope.Folder("Small/")), TimelineGrouping.DAY, NOW))
        assertTrue("folder sections: $folder", "COVERING INDEX index_media_relativePath_takenAt" in folder)
        assertTrue("count: ${plan(LibraryQueryBuilder.count(LibraryQuery(), NOW))}", "COVERING INDEX" in plan(LibraryQueryBuilder.count(LibraryQuery(), NOW)))
    }

    @Test
    fun scopesMadeOfAListAreDrivenFromTheListSoTheirCostFollowsTheirSizeNotTheLibraryS() {
        val scopes = mapOf(
            "album" to LibraryScope.Album(1),
            "hidden" to LibraryScope.Hidden,
            "person" to LibraryScope.Person(1),
            "semantic" to LibraryScope.Semantic(7),
        )
        for ((name, scope) in scopes) {
            val plan = plan(LibraryQueryBuilder.media(LibraryQuery(scope), NOW))
            assertTrue("$name reads the whole library: $plan", "SCAN m" !in plan)
        }
        val edited = plan(LibraryQueryBuilder.media(LibraryQuery(filters = LibraryFilters(editedOnly = true)), NOW))
        assertTrue("edited reads the whole library: $edited", "SCAN m" !in edited)
    }

    private companion object {
        const val ROWS = 20_000L
        const val NOW = 1_800_000_000_000L
    }
}
