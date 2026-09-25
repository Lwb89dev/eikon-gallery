package app.eikon.gallery.data.db

import app.eikon.gallery.domain.search.TimeRange
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The memory queries, run on a real SQLite. */
class MemorySqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaFiles.newest().forEach { execute(it) }
    }

    @After
    fun close() = db.close()

    private fun execute(sql: String) = db.createStatement().use { it.execute(sql) }

    /** [utc] is "yyyy-mm-dd hh:mm" in UTC. */
    private fun media(id: Long, utc: String, video: Boolean = false, screenshot: Boolean = false, favorite: Boolean = false, w: Int = 4000, h: Int = 3000) = execute(
        "INSERT INTO media (id, displayName, mimeType, isVideo, takenAt, addedAt, modifiedAt, width, height, durationMs, sizeBytes, relativePath, isFavorite, isScreenshot, isScreenRecording, isPanorama, isRaw) " +
            "VALUES ($id, 'f$id', 'image/jpeg', ${if (video) 1 else 0}, (strftime('%s', '$utc:00') * 1000), 0, 0, $w, $h, 0, 0, 'DCIM/', ${if (favorite) 1 else 0}, ${if (screenshot) 1 else 0}, 0, 0, 0)",
    )

    private fun rows(sql: String, column: String): List<String> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getString(column) else null }.toList() }
    }

    private fun candidateIds(query: androidx.sqlite.db.SimpleSQLiteQuery): List<Long> = db.prepareStatement(query.sql).use { st ->
        // SimpleSQLiteQuery keeps its bind arguments; bind them through its own API onto a plain JDBC statement.
        val binder = object : androidx.sqlite.db.SupportSQLiteProgram {
            override fun bindNull(index: Int) = st.setObject(index, null)
            override fun bindLong(index: Int, value: Long) = st.setLong(index, value)
            override fun bindDouble(index: Int, value: Double) = st.setDouble(index, value)
            override fun bindString(index: Int, value: String) = st.setString(index, value)
            override fun bindBlob(index: Int, value: ByteArray) = st.setBytes(index, value)
            override fun clearBindings() = Unit
            override fun close() = Unit
        }
        query.bindTo(binder)
        st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong("id") else null }.toList() }
    }

    private fun period(from: String, to: String): TimeRange = db.createStatement().use { st ->
        st.executeQuery("SELECT strftime('%s','$from 00:00:00') * 1000, strftime('%s','$to 00:00:00') * 1000").use { rs -> rs.next(); TimeRange(rs.getLong(1), rs.getLong(2)) }
    }

    @Test
    fun dayCountsLeaveOutScreenshotsAndHiddenPhotos() {
        media(1, "2025-09-25 12:00"); media(2, "2025-09-25 13:00"); media(3, "2025-09-25 14:00", screenshot = true); media(4, "2025-09-25 15:00"); media(5, "2025-09-26 12:00", video = true)
        execute("INSERT INTO hidden_media VALUES (4, 0)")

        val counts = db.createStatement().use { st -> st.executeQuery(MemoryQueries.DAY_COUNTS).use { rs -> generateSequence { if (rs.next()) rs.getString("day") to rs.getInt("count") else null }.toMap() } }

        assertEquals(2, counts.getValue("2025-09-25")) // 1 and 2; not the screenshot, not the hidden one
        assertEquals(1, counts.getValue("2025-09-26")) // a video counts for a day
    }

    @Test
    fun personYearsCountOnlyNamedVisibleNotIgnoredPeople() {
        (1L..4L).forEach { media(it, "2025-05-0$it 12:00") }
        media(5, "2024-05-01 12:00")
        execute("INSERT INTO person (id, name, isFavorite, isHidden, isPinned, createdAt) VALUES (1, 'Marco', 1, 0, 0, 0), (2, NULL, 0, 0, 0, 0), (3, 'Hidden', 0, 1, 0, 0)")
        listOf(1L to 1L, 2L to 1L, 3L to 1L, 5L to 1L).forEach { (m, p) -> execute("INSERT INTO face (mediaId, `left`, top, `right`, bottom, score, vector, personId, ignored) VALUES ($m, 0, 0, 1, 1, 1, x'00', $p, 0)") }
        execute("INSERT INTO face (mediaId, `left`, top, `right`, bottom, score, vector, personId, ignored) VALUES (4, 0, 0, 1, 1, 1, x'00', 1, 1)") // ignored
        execute("INSERT INTO face (mediaId, `left`, top, `right`, bottom, score, vector, personId, ignored) VALUES (1, 0, 0, 1, 1, 1, x'00', 2, 0), (2, 0, 0, 1, 1, 1, x'00', 3, 0)")
        execute("INSERT INTO hidden_media VALUES (3, 0)")

        val rows = db.createStatement().use { st -> st.executeQuery(MemoryQueries.PERSON_YEARS).use { rs -> generateSequence { if (rs.next()) "${rs.getLong("personId")}/${rs.getInt("year")}/${rs.getInt("count")}/${rs.getInt("isFavorite")}" else null }.toList() } }

        // Marco is in photos 1, 2, 3 (2025) and 5 (2024); 3 is hidden. His ignored face in photo 4 and the unnamed and hidden people do not count.
        assertEquals(setOf("1/2025/2/1", "1/2024/1/1"), rows.toSet()) // person / year / photos / favorite
    }

    @Test
    fun candidatesAreVisiblePhotosOfThePeriodOldestFirst() {
        media(1, "2025-08-14 10:00"); media(2, "2025-08-14 09:00"); media(3, "2025-08-15 09:00", video = true); media(4, "2025-08-16 09:00", screenshot = true)
        media(5, "2025-08-20 09:00"); media(6, "2025-08-14 11:00"); execute("INSERT INTO hidden_media VALUES (6, 0)")

        val query = MemoryQueries.candidates(listOf(period("2025-08-14", "2025-08-21")), null, emptySet(), emptySet())

        assertEquals(listOf(2L, 1L, 5L), candidateIds(query))
    }

    @Test
    fun severalPeriodsAreAlternatives() {
        media(1, "2024-08-14 10:00"); media(2, "2025-08-14 10:00"); media(3, "2023-08-14 10:00")
        val query = MemoryQueries.candidates(listOf(period("2024-08-14", "2024-08-15"), period("2025-08-14", "2025-08-15")), null, emptySet(), emptySet())
        assertEquals(listOf(1L, 2L), candidateIds(query))
    }

    @Test
    fun aPersonsMemoryOnlyHasTheirPhotosAndLessOfSomeoneLeavesThemOut() {
        media(1, "2025-05-01 10:00"); media(2, "2025-05-02 10:00"); media(3, "2025-05-03 10:00")
        execute("INSERT INTO person (id, name, isFavorite, isHidden, isPinned, createdAt) VALUES (1, 'A', 0, 0, 0, 0), (2, 'B', 0, 0, 0, 0)")
        execute("INSERT INTO face (mediaId, `left`, top, `right`, bottom, score, vector, personId, ignored) VALUES (1, 0, 0, 1, 1, 1, x'00', 1, 0), (2, 0, 0, 1, 1, 1, x'00', 1, 0), (2, 0, 0, 1, 1, 1, x'00', 2, 0)")
        val year = listOf(period("2025-01-01", "2026-01-01"))

        assertEquals(listOf(1L, 2L), candidateIds(MemoryQueries.candidates(year, person = 1, lessOf = emptySet(), excludedDays = emptySet())))
        assertEquals(listOf(1L, 3L), candidateIds(MemoryQueries.candidates(year, person = null, lessOf = setOf(2L), excludedDays = emptySet())))
    }

    @Test
    fun anExcludedDayLeavesItsPhotosOut() {
        media(1, "2025-08-14 10:00"); media(2, "2025-08-15 10:00")
        val query = MemoryQueries.candidates(listOf(period("2025-08-01", "2025-09-01")), null, emptySet(), setOf("2025-08-14"))
        assertEquals(listOf(2L), candidateIds(query))
    }

    @Test
    fun userChoicesRoundTripThroughTheirKeys() {
        val rows = listOf(
            MemoryPreferenceEntity("memory:otd:9:25:2024", 1, 0), MemoryPreferenceEntity("kind:TRIP", 2, 0),
            MemoryPreferenceEntity("kind:NOPE", 1, 0), MemoryPreferenceEntity("person:7", 1, 0), MemoryPreferenceEntity("date:2025-08-14", 1, 0), MemoryPreferenceEntity("date:bad", 1, 0),
        )
        val prefs = app.eikon.gallery.data.memories.MemoryRepository.parse(rows)
        assertEquals(setOf("otd:9:25:2024"), prefs.hidden)
        assertEquals(mapOf(app.eikon.gallery.domain.memories.MemoryKind.TRIP to 2), prefs.fewer)
        assertEquals(setOf(7L), prefs.lessOf)
        assertEquals(setOf(java.time.LocalDate.of(2025, 8, 14)), prefs.excludedDays)
    }
}
