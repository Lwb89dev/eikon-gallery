package app.eikon.gallery.data.places

import android.content.Context
import app.eikon.gallery.data.db.PlacesDao
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.places.GeoPoints
import app.eikon.gallery.domain.places.WorldMap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Place names from the bundled gazetteer and the country names of the device's language. */
class GazetteerNames(private val gazetteer: Gazetteer, private val locale: Locale) : PlaceNames {
    override fun country(code: String): String =
        Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }

    override fun region(key: String): String? = gazetteer.regionDisplayNames[key]

    override fun city(id: Long): String? = gazetteer.city(id)?.name
}

/** Where photos were taken, for the Places list and map. Everything is read from the local database. */
@Singleton
class PlacesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PlacesDao,
    private val gazetteers: GazetteerProvider,
) {
    private val worldLock = Mutex()
    private var world: WorldMap? = null

    /** The country, region, city tree of visible geotagged photos; follows the analysis as it learns places. */
    val tree: Flow<List<PlaceNode>> = dao.observeGroups().map { rows -> PlaceTree.build(rows, names()) }

    /** Every visible geotagged photo's position, for the map. A snapshot: read again when the map is opened. */
    suspend fun points(): GeoPoints = withContext(Dispatchers.Default) {
        val rows = dao.points()
        GeoPoints.ofLatLon(DoubleArray(rows.size) { rows[it].latitude }, DoubleArray(rows.size) { rows[it].longitude })
    }

    /** The country outlines behind the markers, decoded once (about 50,000 points). */
    suspend fun worldMap(): WorldMap = worldLock.withLock {
        world ?: withContext(Dispatchers.Default) {
            WorldMap.decode(context.assets.open(WORLD_ASSET).use { it.readBytes() })
        }.also { world = it }
    }

    suspend fun gazetteer(): Gazetteer = gazetteers.get()

    /** "Rome", "Lazio", "Italy" or, for the unnamed group, null. */
    suspend fun title(place: LibraryScope.Place): String? {
        val names = names()
        return when {
            place.city != null -> names.city(place.city)
            place.region != null -> names.region(place.region)
            place.country != null -> names.country(place.country)
            else -> null
        }
    }

    /** The name of the city nearest to a point, for titling a map cluster; null far from any city. */
    suspend fun nearestCityName(latitude: Double, longitude: Double): String? = gazetteers.get().nearest(latitude, longitude)?.name

    private suspend fun names(): PlaceNames =
        GazetteerNames(gazetteers.get(), context.resources.configuration.locales[0])

    private companion object {
        const val WORLD_ASSET = "places/world.bin"
    }
}
