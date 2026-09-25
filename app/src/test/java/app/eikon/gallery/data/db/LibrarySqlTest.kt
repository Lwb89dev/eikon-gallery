package app.eikon.gallery.data.db

import app.eikon.gallery.domain.CategoryFilter
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.SortDirection
import app.eikon.gallery.domain.SortField
import app.eikon.gallery.domain.TimelineGrouping
import app.eikon.gallery.domain.TimelineLayout
import app.eikon.gallery.domain.TypeFilter
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs the SQL produced by [LibraryQueryBuilder] against a real SQLite, on tables created from the
 * newest schema Room exported (so the test cannot drift from the entities). What it protects is the
 * invariant the grid relies on: section counts add up to the media query, in the same order, so that
 * "section i starts at media index start_i" is true, for every scope and filter.
 *
 * Timestamps sit at 12:00 UTC so every time zone from UTC-11 to UTC+11 sees the same calendar day,
 * which keeps the `localtime` conversion deterministic on any machine.
 */
class LibrarySqlTest {
    private lateinit var db: Connection

    // Days are counted from 2025-01-01. id, day taken, day added, kind, favorite, path.
    private val rows = listOf(
        Row(1, day = 10, added = 30, path = "DCIM/Camera/"),
        Row(2, day = 10, added = 29, video = true, favorite = true, path = "DCIM/Camera/"),
        Row(3, day = 10, added = 28, screenshot = true, path = "Pictures/Screenshots/"),
        Row(4, day = 9, added = 27, favorite = true, path = "DCIM/Camera/"),
        Row(5, day = 5, added = 26, video = true, path = "Movies/"),
        Row(6, day = 5, added = 25, path = "DCIM/Camera/"),
        Row(7, day = 5, added = 24, path = "DCIM/Camera/"),
        Row(8, day = 40, added = 23, favorite = true, path = "Pictures/Screenshots/"),
    )

    @Before
    fun openDatabase() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exportedSchemaStatements().forEach { db.createStatement().use { s -> s.execute(it) } }
        rows.forEach(::insertMedia)
        // Album 1 holds ids 1, 4, 8 (8 is also hidden); id 3 is hidden.
        execute("INSERT INTO album (id, name, createdAt, position) VALUES (1, 'Trip', 0, 0)")
        listOf(1, 4, 8).forEach { execute("INSERT INTO album_item (albumId, mediaId, addedAt) VALUES (1, $it, 0)") }
        listOf(3, 8).forEach { execute("INSERT INTO hidden_media (mediaId, hiddenAt) VALUES ($it, 0)") }
    }

    @After
    fun closeDatabase() = db.close()

    @Test
    fun everythingExcludesHiddenAndKeepsNewestFirstWithIdTieBreak() {
        // Hidden: 3, 8. Remaining by takenAt DESC, id DESC: day10 -> 2,1; day9 -> 4; day5 -> 7,6,5.
        assertEquals(listOf(2L, 1L, 4L, 7L, 6L, 5L), ids(LibraryQuery()))
    }

    @Test
    fun hiddenScopeShowsExactlyTheHiddenItems() {
        assertEquals(listOf(8L, 3L), ids(LibraryQuery(scope = LibraryScope.Hidden)))
    }

    @Test
    fun oldestFirstIsTheExactReverse() {
        val newest = ids(LibraryQuery())
        assertEquals(newest.reversed(), ids(LibraryQuery(direction = SortDirection.OLDEST_FIRST)))
    }

    @Test
    fun sortingByDateAddedUsesTheOtherColumn() {
        assertEquals(listOf(1L, 2L, 4L, 5L, 6L, 7L), ids(LibraryQuery(sortField = SortField.DATE_ADDED)))
    }

    @Test
    fun filtersSelectExactlyTheirRowsAndCombine() {
        assertEquals(setOf(1L, 4L, 6L, 7L), ids(LibraryQuery(filters = LibraryFilters(TypeFilter.PHOTOS))).toSet())
        assertEquals(setOf(2L, 5L), ids(LibraryQuery(filters = LibraryFilters(TypeFilter.VIDEOS))).toSet())
        assertEquals(setOf(2L, 4L), ids(LibraryQuery(filters = LibraryFilters(favoritesOnly = true))).toSet())
        assertEquals(setOf(2L), ids(LibraryQuery(filters = LibraryFilters(TypeFilter.VIDEOS, favoritesOnly = true))).toSet())
        assertEquals(emptySet<Long>(), ids(LibraryQuery(filters = LibraryFilters(category = CategoryFilter.RAW))).toSet())
    }

    @Test
    fun screenshotsThatAreHiddenNeverLeakIntoTheirCategory() {
        // id 3 is the only visible-or-not screenshot in the library and it is hidden.
        val query = LibraryQuery(filters = LibraryFilters(category = CategoryFilter.SCREENSHOTS))
        assertEquals(emptyList<Long>(), ids(query))
        assertEquals(listOf(3L), ids(query.copy(scope = LibraryScope.Hidden)))
    }

    @Test
    fun albumScopeListsOnlyVisibleMembersWithoutDuplicates() {
        // Members 1, 4, 8; 8 is hidden. Newest first: 1 (day 10), 4 (day 9).
        assertEquals(listOf(1L, 4L), ids(LibraryQuery(scope = LibraryScope.Album(1))))
    }

    @Test
    fun albumFilteringStillWorks() {
        val query = LibraryQuery(LibraryScope.Album(1), LibraryFilters(favoritesOnly = true))
        assertEquals(listOf(4L), ids(query))
    }

    @Test
    fun folderScopeMatchesThePathExactly() {
        assertEquals(setOf(1L, 2L, 4L, 6L, 7L), ids(LibraryQuery(scope = LibraryScope.Folder("DCIM/Camera/"))).toSet())
        assertEquals(setOf(5L), ids(LibraryQuery(scope = LibraryScope.Folder("Movies/"))).toSet())
    }

    @Test
    fun aHostilePathIsJustAPathAndReturnsNothing() {
        assertEquals(emptyList<Long>(), ids(LibraryQuery(scope = LibraryScope.Folder("x' OR '1'='1"))))
        assertEquals(6, ids(LibraryQuery()).size) // and the table is intact
    }

    @Test
    fun recentlyAddedUsesTheAddedDateNotTheCaptureDate() {
        val now = utcNoon(60)
        // now = day 60 -> cutoff day 30: only id 1 (added on day 30) qualifies.
        assertEquals(listOf(1L), ids(LibraryQuery(scope = LibraryScope.RecentlyAdded(30)), now))
        // now = day 40 -> cutoff day 10: every visible row was added after that.
        assertEquals(6, ids(LibraryQuery(scope = LibraryScope.RecentlyAdded(30)), utcNoon(40)).size)
    }

    @Test
    fun sectionsMatchTheMediaQueryForEveryScopeFilterAndSort() {
        val queries = buildList {
            for (direction in SortDirection.entries) for (field in SortField.entries) {
                add(LibraryQuery(sortField = field, direction = direction))
                add(LibraryQuery(LibraryScope.Hidden, sortField = field, direction = direction))
                add(LibraryQuery(LibraryScope.Album(1), sortField = field, direction = direction))
                add(LibraryQuery(LibraryScope.Folder("DCIM/Camera/"), sortField = field, direction = direction))
                add(LibraryQuery(filters = LibraryFilters(TypeFilter.PHOTOS, favoritesOnly = true), sortField = field, direction = direction))
            }
        }
        queries.forEach(::assertSectionsMatchMedia)
    }

    @Test
    fun monthGroupingIsCoarserThanDayGrouping() {
        val days = layout(LibraryQuery(), TimelineGrouping.DAY).sections.size
        val months = layout(LibraryQuery(), TimelineGrouping.MONTH).sections.size
        assertTrue("months=$months days=$days", months < days)
    }

    @Test
    fun countAndCoverAgreeWithTheMediaQuery() {
        val query = LibraryQuery(filters = LibraryFilters(favoritesOnly = true))
        val expected = ids(query)
        assertEquals(expected.size, countOf(LibraryQueryBuilder.count(query, NOW)))
        val cover = db.prepareStatement(LibraryQueryBuilder.cover(query, NOW).sql).use { st ->
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong("id") else -1L }
        }
        assertEquals(expected.first(), cover)
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Every item must sit in the section (day) its own timestamp belongs to. */
    private fun assertSectionsMatchMedia(query: LibraryQuery) {
        val ordered = mediaRows(query)
        val layout = layout(query, TimelineGrouping.DAY)
        assertEquals("count for $query", ordered.size, layout.mediaCount)
        ordered.forEachIndexed { index, row ->
            val section = layout.sections[layout.sectionIndexOfMedia(index)]
            val column = if (query.sortField == SortField.DATE_TAKEN) row.takenAt else row.addedAt
            val day = Instant.ofEpochMilli(column).atZone(ZoneOffset.UTC).toLocalDate().toString()
            assertEquals("item ${row.id} in $query", day, section.bucket)
        }
    }

    private class Row(
        val id: Long,
        val day: Int,
        val added: Int,
        val video: Boolean = false,
        val favorite: Boolean = false,
        val screenshot: Boolean = false,
        val path: String,
    )

    private class MediaRow(val id: Long, val takenAt: Long, val addedAt: Long)

    private fun execute(sql: String) {
        db.createStatement().use { it.execute(sql) }
    }

    private fun insertMedia(row: Row) {
        val sql = "INSERT INTO media (id, displayName, mimeType, isVideo, takenAt, addedAt, modifiedAt, " +
            "width, height, durationMs, sizeBytes, relativePath, isFavorite, isScreenshot, isScreenRecording, isPanorama, isRaw) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, ?, ?, ?, 0, 0, 0)"
        db.prepareStatement(sql).use { st ->
            st.setLong(1, row.id)
            st.setString(2, "f${row.id}")
            st.setString(3, if (row.video) "video/mp4" else "image/jpeg")
            st.setInt(4, if (row.video) 1 else 0)
            st.setLong(5, utcNoon(row.day))
            st.setLong(6, utcNoon(row.added))
            st.setLong(7, utcNoon(row.day))
            st.setString(8, row.path)
            st.setInt(9, if (row.favorite) 1 else 0)
            st.setInt(10, if (row.screenshot) 1 else 0)
            st.executeUpdate()
        }
    }

    /** Noon UTC, `daysAfterEpoch` days after 2025-01-01. */
    private fun utcNoon(daysAfterEpoch: Int): Long = (1_735_689_600L + daysAfterEpoch * 86_400L + 12 * 3_600L) * 1000

    private fun ids(query: LibraryQuery, now: Long = NOW): List<Long> = mediaRows(query, now).map { it.id }

    private fun mediaRows(query: LibraryQuery, now: Long = NOW): List<MediaRow> {
        val sql = LibraryQueryBuilder.media(query, now)
        return db.prepareStatement(sql.sql).use { st ->
            sql.args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs ->
                generateSequence { if (rs.next()) MediaRow(rs.getLong("id"), rs.getLong("takenAt"), rs.getLong("addedAt")) else null }.toList()
            }
        }
    }

    private fun layout(query: LibraryQuery, grouping: TimelineGrouping): TimelineLayout {
        val sql = LibraryQueryBuilder.sections(query, grouping, NOW)
        val counts = db.prepareStatement(sql.sql).use { st ->
            sql.args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs ->
                generateSequence { if (rs.next()) rs.getString("bucket") to rs.getInt("count") else null }.toList()
            }
        }
        return TimelineLayout.fromCounts(counts)
    }

    private fun countOf(sql: SqlQuery): Int = db.prepareStatement(sql.sql).use { st ->
        sql.args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
        st.executeQuery().use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

    /**
     * CREATE TABLE / CREATE INDEX statements from the newest schema Room exported. The JSON lists each
     * entity's `tableName`, then its `createSql`, then its indices (whose SQL uses a `${TABLE_NAME}`
     * placeholder), so the table name is tracked while scanning in order.
     */
    private fun exportedSchemaStatements(): List<String> {
        val directory = File("schemas/app.eikon.gallery.data.db.EikonDatabase")
        val newest = directory.listFiles { file -> file.extension == "json" }!!.maxByOrNull { it.nameWithoutExtension.toInt() }!!
        val token = Regex("\"tableName\":\\s*\"(\\w+)\"|\"createSql\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        var table = ""
        val statements = mutableListOf<String>()
        for (match in token.findAll(newest.readText())) {
            if (match.groupValues[1].isNotEmpty()) {
                table = match.groupValues[1]
                continue
            }
            val sql = match.groupValues[2].replace("\\\"", "\"").replace("\${TABLE_NAME}", table)
            if (sql.startsWith("CREATE TABLE") || sql.startsWith("CREATE INDEX")) statements += sql
        }
        return statements
    }

    private companion object {
        val NOW = 1_800_000_000_000L
    }
}
