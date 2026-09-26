package app.eikon.gallery.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The backup's SQL, run on a real SQLite on the tables Room exported: which photos still have to be sent, and how far the backup has got. */
class BackupSqlTest {
    private lateinit var db: Connection

    // id, taken at (larger = newer), video, hidden, modified at
    private val photos = listOf(
        Photo(1, taken = 100), Photo(2, taken = 200), Photo(3, taken = 300, video = true), Photo(4, taken = 400, hidden = true), Photo(5, taken = 500),
    )

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaFiles.newest().forEach { db.createStatement().use { s -> s.execute(it) } }
        photos.forEach(::insert)
    }

    @After
    fun close() = db.close()

    private fun insert(p: Photo) {
        db.prepareStatement(
            "INSERT INTO media (id, displayName, mimeType, isVideo, takenAt, addedAt, modifiedAt, width, height, durationMs, sizeBytes, relativePath, isFavorite, isScreenshot, isScreenRecording, isPanorama, isRaw) " +
                "VALUES (?, 'f', ?, ?, ?, ?, ?, 0, 0, 0, 10, 'DCIM/', 0, 0, 0, 0, 0)",
        ).use { st ->
            st.setLong(1, p.id); st.setString(2, if (p.video) "video/mp4" else "image/jpeg"); st.setInt(3, if (p.video) 1 else 0)
            st.setLong(4, p.taken); st.setLong(5, p.taken); st.setLong(6, p.modified)
            st.executeUpdate()
        }
        if (p.hidden) db.createStatement().use { it.execute("INSERT INTO hidden_media VALUES (${p.id}, 0)") }
    }

    private fun send(id: Long, status: Int = 0, attempts: Int = 0, modified: Long = 1) =
        db.createStatement().use { it.execute("INSERT OR REPLACE INTO backup_item VALUES ($id, $status, $attempts, $modified, 10, 'abc', 0)") }

    private fun pending(videos: Boolean = true, hidden: Boolean = false, maxAttempts: Int = 3, limit: Int = 100): List<Long> =
        query(BackupQueries.PENDING, videos, hidden, maxAttempts, limit)

    private fun count(sql: String, videos: Boolean = true, hidden: Boolean = false): Int = query(sql, videos, hidden).single().toInt()

    /** Room binds parameters by the order they appear in the text. */
    private fun query(sql: String, videos: Boolean, hidden: Boolean, maxAttempts: Int? = null, limit: Int? = null): List<Long> {
        val order = Regex(":(\\w+)").findAll(sql).map { it.groupValues[1] }.toList()
        val values = mapOf("includeVideos" to (if (videos) 1 else 0), "includeHidden" to (if (hidden) 1 else 0), "maxAttempts" to maxAttempts, "limit" to limit)
        val jdbc = sql.replace(Regex(":\\w+"), "?")
        return db.prepareStatement(jdbc).use { st ->
            order.forEachIndexed { i, name -> st.setInt(i + 1, values.getValue(name)!!) }
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong(1) else null }.toList() }
        }
    }

    @Test
    fun everythingIsPendingBeforeAnythingIsSentNewestFirstAndHiddenLeftOut() {
        assertEquals(listOf(5L, 3L, 2L, 1L), pending())
    }

    @Test
    fun videosCanBeLeftOut() {
        assertEquals(listOf(5L, 2L, 1L), pending(videos = false))
    }

    @Test
    fun hiddenPhotosAreSentOnlyWhenAskedFor() {
        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), pending(hidden = true))
    }

    @Test
    fun aPhotoAlreadySentIsNotPendingAgain() {
        send(5); send(2)
        assertEquals(listOf(3L, 1L), pending())
    }

    @Test
    fun aPhotoThatChangedSinceItWasSentIsPendingAgain() {
        send(2, modified = 999) // the file says 1 now
        assertEquals(listOf(5L, 3L, 2L, 1L), pending())
    }

    @Test
    fun aPhotoTheServerRefusedIsTriedAgainOnlyUntilTheLimit() {
        send(5, status = 1, attempts = 2)
        send(2, status = 1, attempts = 3)
        assertEquals(listOf(5L, 3L, 1L), pending(maxAttempts = 3))
        assertEquals(listOf(5L, 3L, 2L, 1L), pending(maxAttempts = 4))
    }

    @Test
    fun theLimitOnHowManyIsHonoured() {
        assertEquals(listOf(5L, 3L), pending(limit = 2))
    }

    @Test
    fun theCountsSayHowFarTheBackupHasGot() {
        send(5); send(2); send(1, status = 1, attempts = 3)
        assertEquals(4, count(BackupQueries.TOTAL))
        assertEquals(2, count(BackupQueries.DONE))
        assertEquals(1, count(BackupQueries.FAILED))
    }

    @Test
    fun theCountsFollowTheSameRulesAsThePendingList() {
        send(5); send(4)
        assertEquals(4, count(BackupQueries.TOTAL, videos = false, hidden = true))
        assertEquals(3, count(BackupQueries.TOTAL, videos = false, hidden = false))
        assertEquals(2, count(BackupQueries.DONE, videos = false, hidden = true))
        assertEquals(1, count(BackupQueries.DONE, videos = false, hidden = false))
    }

    private class Photo(val id: Long, val taken: Long, val video: Boolean = false, val hidden: Boolean = false, val modified: Long = 1)
}
