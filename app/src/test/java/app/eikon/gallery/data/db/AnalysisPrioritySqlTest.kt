package app.eikon.gallery.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The queries that pick what the analysis does next, run on a real SQLite: the photos on screen come out of the same rules as the rest, only restricted to them. */
class AnalysisPrioritySqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaFiles.newest().forEach { execute(it) }
        // Newest last: ids double as ages. 1..5 photos, 6 a video, 7 a photo.
        (1L..7L).forEach { media(it, video = it == 6L) }
    }

    @After
    fun close() = db.close()

    private fun execute(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun media(id: Long, video: Boolean = false) = execute(
        "INSERT INTO media (id, displayName, mimeType, isVideo, takenAt, addedAt, modifiedAt, width, height, durationMs, sizeBytes, relativePath, isFavorite, isScreenshot, isScreenRecording, isPanorama, isRaw) " +
            "VALUES ($id, 'f$id', '${if (video) "video/mp4" else "image/jpeg"}', ${if (video) 1 else 0}, ${id * 1000}, $id, $id, 4, 3, 0, ${id * 10}, 'DCIM/', 0, 0, 0, 0, 0)",
    )

    /** Room expands a list parameter into `?, ?, ...` in the order the parameters appear. */
    private fun ids(sql: String, values: Map<String, Any>): List<Long> {
        val names = Regex(":(\\w+)").findAll(sql).map { it.groupValues[1] }.toList()
        val expanded = sql.replace(Regex(":(\\w+)")) { m ->
            val v = values.getValue(m.groupValues[1])
            if (v is List<*>) v.joinToString(",") { "?" } else "?"
        }
        return db.prepareStatement(expanded).use { st ->
            var index = 1
            for (name in names) {
                val v = values.getValue(name)
                if (v is List<*>) v.forEach { st.setObject(index++, it) } else st.setObject(index++, v)
            }
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong("id") else null }.toList() }
        }
    }

    private fun pending(limit: Int = 100) = ids(IndexQueries.PENDING, mapOf("stage" to "OCR", "maxAttempts" to 3, "limit" to limit))
    private fun among(vararg wanted: Long) = ids(IndexQueries.PENDING_AMONG, mapOf("stage" to "OCR", "maxAttempts" to 3, "ids" to wanted.toList()))

    @Test
    fun theWholeLibraryComesNewestFirstAndVideosAreLeftOut() {
        assertEquals(listOf(7L, 5L, 4L, 3L, 2L, 1L), pending())
    }

    @Test
    fun theOnesOnScreenComeOutNewestFirstWhateverOrderTheyAreAskedIn() {
        assertEquals(listOf(4L, 2L), among(2, 4))
    }

    @Test
    fun aPhotoOnScreenThatIsAlreadyDoneIsNotDoneAgain() {
        execute("INSERT INTO index_state VALUES (4, 'OCR', 1, 0, 0)")
        assertEquals(listOf(2L), among(2, 4))
    }

    @Test
    fun aPhotoThatFailedTooOftenIsLeftAloneEvenOnScreenButOneThatFailedOnceIsRetried() {
        execute("INSERT INTO index_state VALUES (4, 'OCR', 2, 3, 0)")
        execute("INSERT INTO index_state VALUES (2, 'OCR', 2, 1, 0)")
        assertEquals(listOf(2L), among(2, 4))
    }

    @Test
    fun aVideoOnScreenIsNotAnalysed() {
        assertEquals(listOf(5L), among(5, 6))
    }

    @Test
    fun anotherStagesStateDoesNotHideAPhoto() {
        execute("INSERT INTO index_state VALUES (4, 'GEO', 1, 0, 0)")
        assertEquals(listOf(4L), among(4))
    }

    @Test
    fun nothingOnScreenMeansNothingFromThisQuery() {
        assertEquals(emptyList<Long>(), among())
    }
}
