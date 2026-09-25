package app.eikon.gallery.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Photos at one place: how many, and the newest of them as the picture. Any of the three ids may be null. */
class PlaceGroupRow(
    val countryCode: String?,
    val regionKey: String?,
    val cityId: Long?,
    val photoCount: Int,
    val coverMediaId: Long,
    val coverModifiedAt: Long,
    val coverTakenAt: Long,
)

/** Where one photo was taken. */
class GeoPointRow(val mediaId: Long, val latitude: Double, val longitude: Double)

/** One geotagged photo with what Trips needs to work out where and when. */
class GeoShotRow(
    val mediaId: Long,
    val takenAt: Long,
    val latitude: Double,
    val longitude: Double,
    val cityId: Long?,
    val regionKey: String?,
    val countryCode: String?,
)

/** SQL kept as constants so tests can run exactly this text against a real SQLite. */
object PlacesQueries {
    /**
     * One row per (country, region, city) that has visible geotagged photos. The bare `m.id` columns come from the row
     * that has the group's `MAX(m.takenAt)`: SQLite guarantees this for a single MAX, so they describe the newest photo.
     */
    const val GROUPS = """
        SELECT g.countryCode AS countryCode, g.regionKey AS regionKey, g.cityId AS cityId, COUNT(*) AS photoCount,
               m.id AS coverMediaId, m.modifiedAt AS coverModifiedAt, MAX(m.takenAt) AS coverTakenAt
        FROM media_geo g JOIN media m ON m.id = g.mediaId
        WHERE m.id NOT IN (SELECT mediaId FROM hidden_media)
        GROUP BY g.countryCode, g.regionKey, g.cityId
        """

    const val POINTS = """
        SELECT g.mediaId AS mediaId, g.latitude AS latitude, g.longitude AS longitude
        FROM media_geo g JOIN media m ON m.id = g.mediaId
        WHERE m.id NOT IN (SELECT mediaId FROM hidden_media)
        """

    const val SHOTS = """
        SELECT g.mediaId AS mediaId, m.takenAt AS takenAt, g.latitude AS latitude, g.longitude AS longitude,
               g.cityId AS cityId, g.regionKey AS regionKey, g.countryCode AS countryCode
        FROM media_geo g JOIN media m ON m.id = g.mediaId
        WHERE m.id NOT IN (SELECT mediaId FROM hidden_media)
        ORDER BY m.takenAt, m.id
        """
}

@Dao
interface PlacesDao {
    @Query(PlacesQueries.GROUPS)
    fun observeGroups(): Flow<List<PlaceGroupRow>>

    @Query(PlacesQueries.POINTS)
    suspend fun points(): List<GeoPointRow>

    @Query(PlacesQueries.SHOTS)
    suspend fun shots(): List<GeoShotRow>
}
