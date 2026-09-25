package app.eikon.gallery.data.db

import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The places queries and place scopes, run on a real SQLite. */
class PlacesSqlTest {
    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        SchemaFiles.newest().forEach { execute(it) }
        // id, takenAt (ms), position and resolved place
        media(1, 1_000); media(2, 2_000); media(3, 3_000); media(4, 4_000); media(5, 5_000); media(6, 6_000); media(7, 7_000)
        geo(1, 41.9, 12.5, 100, "IT.07", "IT")   // Rome
        geo(2, 41.5, 12.9, 200, "IT.07", "IT")   // Latina
        geo(3, 45.5, 9.2, 300, "IT.09", "IT")    // Milan
        geo(4, 48.8, 2.3, 400, "FR.11", "FR")    // Paris
        geo(5, -70.0, 10.0, null, null, null)    // Antarctica: too far from any city
        geo(6, 41.9, 12.5, 100, "IT.07", "IT")   // Rome, hidden
        execute("INSERT INTO hidden_media (mediaId, hiddenAt) VALUES (6, 0)")
    }

    @After
    fun close() = db.close()

    private fun execute(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun media(id: Long, takenAt: Long) = execute(
        "INSERT INTO media (id, displayName, mimeType, isVideo, takenAt, addedAt, modifiedAt, width, height, durationMs, sizeBytes, relativePath, isFavorite, isScreenshot, isScreenRecording, isPanorama, isRaw) " +
            "VALUES ($id, 'f$id', 'image/jpeg', 0, $takenAt, $takenAt, ${id * 10}, 0, 0, 0, 0, 'DCIM/', 0, 0, 0, 0, 0)",
    )

    private fun geo(id: Long, lat: Double, lon: Double, city: Long?, region: String?, country: String?) = execute(
        "INSERT INTO media_geo VALUES ($id, $lat, $lon, ${city ?: "NULL"}, ${country?.let { "'$it'" } ?: "NULL"}, ${region?.let { "'$it'" } ?: "NULL"})",
    )

    private fun photos(scope: LibraryScope): List<Long> {
        val sql = LibraryQueryBuilder.media(LibraryQuery(scope = scope))
        return db.prepareStatement(sql.sql).use { st ->
            sql.args.forEachIndexed { i, arg -> st.setObject(i + 1, arg) }
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong("id") else null }.toList() }
        }
    }

    @Test
    fun theGroupsListEveryPlaceWithItsVisiblePhotosAndNewestPhotoAsCover() {
        val rows = db.createStatement().use { st ->
            st.executeQuery(PlacesQueries.GROUPS).use { rs ->
                generateSequence {
                    if (rs.next()) listOf(rs.getString("countryCode"), rs.getString("regionKey"), rs.getObject("cityId"), rs.getInt("photoCount"), rs.getLong("coverMediaId"), rs.getLong("coverTakenAt")) else null
                }.toList()
            }
        }
        val byCity = rows.associateBy { it[2] }
        assertEquals(5, rows.size)
        assertEquals(1, byCity.getValue(100).let { it[3] }) // photo 6 is hidden and does not count
        assertEquals(1L, byCity.getValue(100)[4])
        assertEquals(listOf("null", "null", "null", "1", "5", "5000"), byCity.getValue(null).map { "$it" })
    }

    @Test
    fun aCityRegionOrCountryScopeSelectsItsVisiblePhotos() {
        assertEquals(listOf(1L), photos(LibraryScope.Place(city = 100)))
        assertEquals(setOf(1L, 2L), photos(LibraryScope.Place(region = "IT.07")).toSet())
        assertEquals(setOf(1L, 2L, 3L), photos(LibraryScope.Place(country = "IT")).toSet())
        assertEquals(listOf(4L), photos(LibraryScope.Place(country = "FR")))
    }

    @Test
    fun theUnnamedPlaceScopeSelectsPhotosWithAPositionButNoCity() {
        assertEquals(listOf(5L), photos(LibraryScope.Place(unknown = true)))
    }

    @Test
    fun anAreaSelectsPhotosInsideTheBoxOnly() {
        assertEquals(setOf(1L, 2L), photos(LibraryScope.Area(41.0, 42.5, 12.0, 13.0)).toSet())
        assertEquals(listOf(4L), photos(LibraryScope.Area(48.0, 49.0, 2.0, 3.0)))
        assertEquals(emptyList<Long>(), photos(LibraryScope.Area(0.0, 1.0, 0.0, 1.0)))
    }

    @Test
    fun aPeriodSelectsPhotosByDateWhetherOrNotTheyHaveAPositionAndNeverHiddenOnes() {
        assertEquals(setOf(2L, 3L, 7L), photos(LibraryScope.Between(2_000, 4_000)).toSet() + photos(LibraryScope.Between(7_000, 8_000)))
        assertEquals(setOf(4L, 5L, 7L), photos(LibraryScope.Between(4_000, 8_000)).filter { it != 6L }.toSet())
        assertEquals(false, 6L in photos(LibraryScope.Between(0, 100_000)))
    }

    @Test
    fun theSameSqlSelectsTheShotsTripsWorkOut() {
        val shots = db.createStatement().use { st -> st.executeQuery(PlacesQueries.SHOTS).use { rs -> generateSequence { if (rs.next()) rs.getLong("mediaId") else null }.toList() } }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), shots) // time order, hidden photo 6 left out
    }
}
