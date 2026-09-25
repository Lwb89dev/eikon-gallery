package app.eikon.gallery.data.places

import android.content.Context
import app.eikon.gallery.data.MediaRepository
import app.eikon.gallery.data.db.PlacesDao
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.memories.NamedTrip
import app.eikon.gallery.domain.places.GeoShot
import app.eikon.gallery.domain.places.Trip
import app.eikon.gallery.domain.places.TripDetector
import app.eikon.gallery.domain.places.TripPlace
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** A trip ready to show: what the rule found, what it is called, and what the library holds for those days. */
class TripItem(
    val trip: Trip,
    /** "Sicily", "France · Spain", or null when the photos' places are unknown. */
    val title: String?,
    /** The city nearest to where the trip started from (home), for the explanation. */
    val homeName: String?,
    /** Every photo and video of those days, with or without a position. */
    val itemCount: Int,
    val cover: MediaItem?,
)

/** Turns the geotagged photos into trips (see [TripDetector] for the rules) and names them. */
@Singleton
class TripsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: PlacesDao,
    private val gazetteers: GazetteerProvider,
    private val media: MediaRepository,
) {
    /** The trips with their names, without looking up what the library holds for each: cheap enough for planning memories. */
    suspend fun named(): List<NamedTrip> = withContext(Dispatchers.Default) {
        val names = GazetteerNames(gazetteers.get(), context.resources.configuration.locales[0])
        detect().map { NamedTrip(it, title(it.place, names)) }
    }

    suspend fun trips(): List<TripItem> = withContext(Dispatchers.Default) {
        val gazetteer = gazetteers.get()
        val names = GazetteerNames(gazetteer, context.resources.configuration.locales[0])
        detect().map { item(it, gazetteer, names) }
    }

    private suspend fun detect(): List<Trip> {
        val shots = dao.shots().map { GeoShot(it.mediaId, it.takenAt, it.latitude, it.longitude, it.cityId, it.regionKey, it.countryCode) }
        return TripDetector(ZoneId.systemDefault()).detect(shots)
    }

    private suspend fun item(trip: Trip, gazetteer: Gazetteer, names: PlaceNames): TripItem {
        val query = LibraryQuery(scope = LibraryScope.Between(trip.startMillis, trip.endMillis), filters = LibraryFilters.NONE)
        return TripItem(
            trip = trip,
            title = title(trip.place, names),
            homeName = gazetteer.nearest(trip.homeLatitude, trip.homeLongitude)?.name,
            itemCount = media.count(query).first(),
            cover = media.cover(query).first(),
        )
    }

    private fun title(place: TripPlace, names: PlaceNames): String? = when (place) {
        is TripPlace.City -> names.city(place.id)
        is TripPlace.Region -> names.region(place.key)
        is TripPlace.Country -> place.codes.joinToString(" · ") { names.country(it) }
        TripPlace.Unknown -> null
    }
}
