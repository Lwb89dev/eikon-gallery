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
import app.eikon.gallery.domain.search.DateSpec
import app.eikon.gallery.domain.search.PlaceMatch
import app.eikon.gallery.domain.search.SearchSpec
import app.eikon.gallery.domain.search.SearchTerm
import app.eikon.gallery.domain.search.TimeRange
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
        seedSearchIndex()
        // Edits (the recipe text does not matter here) on 1, 4 and 6, and on 8, which is hidden.
        listOf(1, 4, 6, 8).forEach { execute("INSERT INTO edit_recipe VALUES ($it, 'eikon-edit 1', 0, 0)") }
    }

    /**
     * Words and places for search tests. 1: receipt from Rome; 2: video from Milan; 3: hidden screenshot
     * mentioning IKEA; 4: "Città" text, Naples; 5-7: plain camera files with no analysis data; 8: hidden.
     */
    private fun seedSearchIndex() {
        val text = mapOf(
            1L to ("IMG_20250110_101010" to "Ricevuta IKEA Roma"),
            2L to ("VID_20250115" to ""),
            3L to ("Screenshot_2025" to "ikea segreto"),
            4L to ("IMG_0004" to "Città di Napoli"),
            5L to ("IMG_0005" to ""), 6L to ("IMG_0006" to ""), 7L to ("holidayPhoto" to ""), 8L to ("IMG_0008" to "ikea"),
        )
        text.forEach { (id, pair) ->
            execute("INSERT INTO media_search (rowid, filename, ocr) VALUES ($id, '${pair.first.lowercase().replace("_", " ")}', '${pair.second}')")
        }
        execute("INSERT INTO media_geo VALUES (1, 41.9, 12.5, 3169070, 'IT', 'IT.07')")
        execute("INSERT INTO media_geo VALUES (2, 45.4, 9.2, 3173435, 'IT', 'IT.09')")
        execute("INSERT INTO media_geo VALUES (4, 40.8, 14.3, 3172394, 'IT', 'IT.04')")
        execute("INSERT INTO media_geo VALUES (5, 48.8, 2.3, 2988507, 'FR', 'FR.11')")
    }

    private fun search(spec: SearchSpec, scope: LibraryScope? = null) =
        ids(LibraryQuery(scope = scope ?: LibraryScope.Search(spec)))

    private val rome = PlaceMatch(cityIds = setOf(3169070L))
    private val italy = PlaceMatch(countryCodes = setOf("IT"))
    private val lombardy = PlaceMatch(regionKeys = setOf("IT.09"))

    @Test
    fun searchByTextFindsOcrWordsCaseAndAccentInsensitively() {
        assertEquals(listOf(1L), search(SearchSpec(listOf(SearchTerm("ricevuta")))))
        assertEquals(listOf(4L), search(SearchSpec(listOf(SearchTerm("citta")))))
        assertEquals(listOf(4L), search(SearchSpec(listOf(SearchTerm("napoli")))))
    }

    @Test
    fun searchByFileNameUsesPrefixes() {
        assertEquals(listOf(1L), search(SearchSpec(listOf(SearchTerm("2025")))).filter { it == 1L })
        assertEquals(listOf(2L), search(SearchSpec(listOf(SearchTerm("vid")))))
        assertEquals(listOf(7L), search(SearchSpec(listOf(SearchTerm("holiday")))))
    }

    @Test
    fun everyTermMustMatch() {
        assertEquals(listOf(1L), search(SearchSpec(listOf(SearchTerm("ricevuta"), SearchTerm("ikea")))))
        assertEquals(emptyList<Long>(), search(SearchSpec(listOf(SearchTerm("ricevuta"), SearchTerm("napoli")))))
    }

    @Test
    fun hiddenItemsNeverAppearInSearchEvenWhenTheirTextMatches() {
        // ids 3 and 8 mention IKEA but are hidden; only 1 is returned.
        assertEquals(listOf(1L), search(SearchSpec(listOf(SearchTerm("ikea")))))
    }

    @Test
    fun aPlaceTermMatchesByCityRegionOrCountry() {
        assertEquals(listOf(1L), search(SearchSpec(listOf(SearchTerm("roma", rome)))))
        assertEquals(listOf(2L), search(SearchSpec(listOf(SearchTerm("lombardia", lombardy)))))
        assertEquals(listOf(1L, 2L, 4L), search(SearchSpec(listOf(SearchTerm("italia", italy)))).sortedBy { it })
    }

    @Test
    fun aPlaceWordAlsoMatchesTheSameWordInText() {
        // "roma" is in the OCR of photo 1 and is also Rome; both routes lead to the same single photo.
        assertEquals(listOf(1L), search(SearchSpec(listOf(SearchTerm("roma", rome)))))
    }

    @Test
    fun dateRangesMonthsAndDaysFilterByCaptureTime() {
        // taken: id1/2 day 10 (Jan 11 2025), id4 day 9 (Jan 10), id5-7 day 5 (Jan 6), id8 day 40 (Feb 10).
        fun range(fromDay: Int, toDay: Int) = DateSpec.Range(TimeRange(utcNoon(fromDay) - 43_200_000L, utcNoon(toDay) - 43_200_000L))
        assertEquals(setOf(1L, 2L), search(SearchSpec(dates = listOf(range(10, 11)))).toSet())
        assertEquals(setOf(1L, 2L, 4L, 5L, 6L, 7L), search(SearchSpec(dates = listOf(DateSpec.Months(setOf(1))))).toSet())
        assertEquals(setOf(4L), search(SearchSpec(dates = listOf(DateSpec.MonthDay(1, 10)))).toSet())
    }

    @Test
    fun severalDatesAreAlternatives() {
        val jan10 = DateSpec.MonthDay(1, 10)
        val jan6 = DateSpec.MonthDay(1, 6)
        assertEquals(setOf(4L, 5L, 6L, 7L), search(SearchSpec(dates = listOf(jan10, jan6))).toSet())
    }

    @Test
    fun parsedFiltersApplyToSearchResults() {
        val onlyVideos = SearchSpec(dates = listOf(DateSpec.Months(setOf(1))), filters = LibraryFilters(TypeFilter.VIDEOS))
        assertEquals(setOf(2L, 5L), search(onlyVideos).toSet())
        val onlyFavorites = SearchSpec(filters = LibraryFilters(favoritesOnly = true))
        assertEquals(setOf(2L, 4L), search(onlyFavorites).toSet())
    }

    @Test
    fun textPlaceAndDateCombine() {
        val spec = SearchSpec(listOf(SearchTerm("ikea"), SearchTerm("roma", rome)), listOf(DateSpec.Months(setOf(1))))
        assertEquals(listOf(1L), search(spec))
        val wrongMonth = SearchSpec(listOf(SearchTerm("ikea")), listOf(DateSpec.Months(setOf(7))))
        assertEquals(emptyList<Long>(), search(wrongMonth))
    }

    @Test
    fun userTextCannotInjectSqlOrFtsSyntax() {
        val hostile = listOf("x' OR '1'='1", "\"quoted\"", "a AND b", "roma OR napoli", "NEAR(a b)", "col:val", "*", "-)(")
        hostile.forEach { word ->
            val result = runCatching { search(SearchSpec(listOf(SearchTerm(word)))) }
            assertTrue("$word must not throw: ${result.exceptionOrNull()}", result.isSuccess)
        }
        assertEquals(6, ids(LibraryQuery()).size) // and the table is intact
    }

    // --- what photos show (semantic hits) ----------------------------------------------------------------

    private fun setHits(vararg ids: Long, queryId: Long = 7L) {
        execute("DELETE FROM search_hit WHERE queryId = $queryId")
        ids.forEach { execute("INSERT INTO search_hit VALUES ($queryId, $it, 0.3)") }
    }

    @Test
    fun aPhotoMatchedByWhatItShowsSatisfiesTheWordsEvenWithoutTheWordInItsText() {
        setHits(6, 7)
        val spec = SearchSpec(listOf(SearchTerm("cane")), semanticQuery = 7L)
        assertEquals(setOf(6L, 7L), search(spec).toSet())
    }

    @Test
    fun hitsAreAddedToTextMatchesNotSubstitutedForThem() {
        setHits(6)
        val spec = SearchSpec(listOf(SearchTerm("ricevuta")), semanticQuery = 7L)
        assertEquals(setOf(1L, 6L), search(spec).toSet())
    }

    @Test
    fun eachQueryReadsOnlyItsOwnHits() {
        setHits(6, queryId = 7L)
        setHits(7, queryId = 8L)
        assertEquals(listOf(6L), search(SearchSpec(listOf(SearchTerm("cane")), semanticQuery = 7L)))
        assertEquals(listOf(7L), search(SearchSpec(listOf(SearchTerm("cane")), semanticQuery = 8L)))
    }

    @Test
    fun hitsAreIgnoredUnlessTheSearchIsMarkedSemantic() {
        setHits(6, 7)
        assertEquals(emptyList<Long>(), search(SearchSpec(listOf(SearchTerm("cane")), semanticQuery = null)))
    }

    @Test
    fun hitsStillHonourDatesPlacesFiltersAndHiding() {
        setHits(1, 2, 3, 4, 5, 8) // 3 and 8 are hidden
        val words = listOf(SearchTerm("cane"))

        assertEquals(setOf(1L, 2L, 4L, 5L), search(SearchSpec(words, semanticQuery = 7L)).toSet())
        assertEquals(setOf(2L, 5L), search(SearchSpec(words, filters = LibraryFilters(type = TypeFilter.VIDEOS), semanticQuery = 7L)).toSet())
        assertEquals(listOf(1L), search(SearchSpec(words + SearchTerm("roma", rome), semanticQuery = 7L)))
        assertEquals(setOf(4L), search(SearchSpec(words, listOf(DateSpec.MonthDay(1, 10)), semanticQuery = 7L)).toSet())
    }

    @Test
    fun aPlaceWordIsNeverMatchedByLookingAtThePhotos() {
        setHits(6, 7)
        // Only place terms: nothing to look for in the pictures, so the hits must not widen the result.
        assertEquals(listOf(1L), search(SearchSpec(listOf(SearchTerm("roma", rome)), semanticQuery = 7L)))
    }

    @Test
    fun semanticResultsKeepTheirDateSectionsInStep() {
        setHits(1, 2, 4, 5, 6, 7)
        assertSectionsMatchMedia(LibraryQuery(scope = LibraryScope.Search(SearchSpec(listOf(SearchTerm("cane")), semanticQuery = 7L))))
    }

    @Test
    fun aSemanticScopeListsTheVisibleHitsOfThatQueryOnly() {
        setHits(1, 3, 4, 8, queryId = 5L) // 3 and 8 are hidden
        setHits(6, queryId = 6L)

        assertEquals(setOf(1L, 4L), ids(LibraryQuery(scope = LibraryScope.Semantic(5))).toSet())
        assertEquals(listOf(6L), ids(LibraryQuery(scope = LibraryScope.Semantic(6))))
        assertEquals(emptyList<Long>(), ids(LibraryQuery(scope = LibraryScope.Semantic(7))))
    }

    @Test
    fun searchSectionsMatchTheSearchResults() {
        val specs = listOf(
            SearchSpec(listOf(SearchTerm("italia", italy))),
            SearchSpec(dates = listOf(DateSpec.Months(setOf(1)))),
            SearchSpec(listOf(SearchTerm("img"))),
        )
        specs.forEach { assertSectionsMatchMedia(LibraryQuery(scope = LibraryScope.Search(it))) }
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
    fun theEditedFilterSelectsPhotosWithAnEditAndCombinesWithTheOthers() {
        assertEquals(setOf(1L, 4L, 6L), ids(LibraryQuery(filters = LibraryFilters(editedOnly = true))).toSet())
        assertEquals(setOf(4L), ids(LibraryQuery(filters = LibraryFilters(favoritesOnly = true, editedOnly = true))).toSet())
        assertEquals(emptySet<Long>(), ids(LibraryQuery(filters = LibraryFilters(TypeFilter.VIDEOS, editedOnly = true))).toSet())
    }

    @Test
    fun anEditedPhotoThatIsHiddenStaysHiddenEvenWithTheEditedFilter() {
        val edited = LibraryFilters(editedOnly = true)
        assertTrue(8L !in ids(LibraryQuery(filters = edited)))
        assertEquals(listOf(8L), ids(LibraryQuery(scope = LibraryScope.Hidden, filters = edited)))
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
                add(LibraryQuery(filters = LibraryFilters(editedOnly = true), sortField = field, direction = direction))
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
            if (sql.startsWith("CREATE")) statements += sql
        }
        return statements
    }

    private companion object {
        val NOW = 1_800_000_000_000L
    }
}
