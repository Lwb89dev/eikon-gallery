package app.eikon.gallery.data.db

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The queries behind duplicate finding, run on a real SQLite. */
class DuplicatesSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaFiles.newest().forEach { execute(it) }
    }

    @After
    fun close() = db.close()

    private fun execute(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun media(id: Long, size: Long, video: Boolean = false, modified: Long = id * 10, takenAt: Long = id * 1000) = execute(
        "INSERT INTO media (id, displayName, mimeType, isVideo, takenAt, addedAt, modifiedAt, width, height, durationMs, sizeBytes, relativePath, isFavorite, isScreenshot, isScreenRecording, isPanorama, isRaw) " +
            "VALUES ($id, 'f$id', '${if (video) "video/mp4" else "image/jpeg"}', ${if (video) 1 else 0}, $takenAt, $id, $modified, 4000, 3000, 0, $size, 'DCIM/', 0, 0, 0, 0, 0)",
    )

    private fun ids(sql: String, vararg args: Any): List<Long> = db.prepareStatement(sql.replace(":maxAttempts", "?").replace(":limit", "?")).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong("id") else null }.toList() }
    }

    private fun pendingPerceptual() = ids(DuplicateQueries.PENDING_PERCEPTUAL, 3, 100)
    private fun pendingContent() = ids(DuplicateQueries.PENDING_CONTENT, 3, 100)

    @Test
    fun aPhotoWithNoFingerprintYetIsPendingButAVideoIsNot() {
        media(1, 100); media(2, 100, video = true)
        assertEquals(listOf(1L), pendingPerceptual())
    }

    @Test
    fun aFingerprintedPhotoIsDoneUntilTheFileChanges() {
        media(1, 100, modified = 10)
        execute("INSERT INTO index_state VALUES (1, 'PHASH', 1, 0, 0)")
        execute("INSERT INTO perceptual_hash VALUES (1, 5, 10)")
        assertEquals(emptyList<Long>(), pendingPerceptual())

        execute("UPDATE media SET modifiedAt = 99 WHERE id = 1") // edited since
        assertEquals(listOf(1L), pendingPerceptual())
    }

    @Test
    fun aPhotoThatFailedTooOftenOrHadNothingToReadIsLeftAlone() {
        media(1, 100); media(2, 100); media(3, 100)
        execute("INSERT INTO index_state VALUES (1, 'PHASH', 2, 3, 0)") // failed three times
        execute("INSERT INTO index_state VALUES (2, 'PHASH', 3, 0, 0)") // skipped
        execute("INSERT INTO index_state VALUES (3, 'PHASH', 2, 1, 0)") // failed once: retried
        assertEquals(listOf(3L), pendingPerceptual())
    }

    @Test
    fun onlyFilesThatShareASizeWithAnotherOfTheSameKindAreHashedByContent() {
        media(1, 500); media(2, 500) // twins
        media(3, 700) // unique size
        media(4, 500, video = true) // same size as 1 and 2 but a video: no twin among videos
        media(5, 900, video = true); media(6, 900, video = true) // video twins
        assertEquals(setOf(1L, 2L, 5L, 6L), pendingContent().toSet())
    }

    @Test
    fun aHashedFileIsDoneAndAFileWhoseTwinWasDeletedIsNoLongerACandidate() {
        media(1, 500); media(2, 500)
        execute("INSERT INTO content_hash VALUES (1, 'abc', 10)")
        assertEquals(listOf(2L), pendingContent())

        execute("DELETE FROM media WHERE id = 2")
        assertEquals(emptyList<Long>(), pendingContent())
    }

    @Test
    fun aNewTwinMakesAnEarlierUniqueFileACandidate() {
        media(1, 500)
        assertEquals(emptyList<Long>(), pendingContent())
        media(2, 500)
        assertEquals(setOf(1L, 2L), pendingContent().toSet())
    }

    @Test
    fun aStaleContentHashIsRedoneAndAFailedFileRetriedOnlyAFewTimes() {
        media(1, 500, modified = 10); media(2, 500)
        execute("INSERT INTO content_hash VALUES (1, 'abc', 5)") // hashed from an older version of the file
        execute("INSERT INTO index_state VALUES (2, 'FILEHASH', 2, 3, 0)")
        assertEquals(listOf(1L), pendingContent())
    }

    @Test
    fun candidatesAreVisibleFilesWithCurrentHashesOnly() {
        media(1, 500, modified = 10); media(2, 500, modified = 20); media(3, 500, modified = 30); media(4, 500, modified = 40)
        execute("INSERT INTO content_hash VALUES (1, 'abc', 10)")
        execute("INSERT INTO content_hash VALUES (2, 'abc', 999)") // stale
        execute("INSERT INTO perceptual_hash VALUES (3, 7, 30)")
        execute("INSERT INTO content_hash VALUES (4, 'abc', 40)")
        execute("INSERT INTO hidden_media VALUES (4, 0)")

        val found = ids(DuplicateQueries.CANDIDATES)

        assertEquals(setOf(1L, 3L), found.toSet())
    }

    @Test
    fun thePerceptualHashKeepsAll64BitsInAnIntegerColumn() {
        media(1, 500)
        val hash = -0x0123456789ABCDEFL
        execute("INSERT INTO perceptual_hash VALUES (1, $hash, 10)")
        val stored = db.createStatement().use { st -> st.executeQuery("SELECT hash FROM perceptual_hash").use { rs -> rs.next(); rs.getLong(1) } }
        assertEquals(hash, stored)
    }
}
