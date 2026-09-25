package app.eikon.gallery.data.metadata

import android.content.Context
import app.eikon.gallery.data.db.MediaGeoEntity
import app.eikon.gallery.data.faces.PeopleRepository
import app.eikon.gallery.data.indexing.IndexingRepository
import app.eikon.gallery.data.places.GazetteerProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** What the background analysis has learned about a photo, ready to show in the info panel. */
data class IndexedInfo(
    /** "Rome, Lazio, Italy": the nearest known city, its region and country; null if unknown. */
    val place: String?,
    /** Text found inside the photo, if any. */
    val text: String?,
    /** Names of the named people in the photo, in order of appearance. */
    val people: List<String> = emptyList(),
    /** Faces in the photo whose person has no name yet. */
    val unnamedFaces: Int = 0,
)

@Singleton
class IndexedInfoReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val indexing: IndexingRepository,
    private val gazetteers: GazetteerProvider,
    private val people: PeopleRepository,
) {
    suspend fun read(mediaId: Long): IndexedInfo {
        val faces = people.facesOfPhoto(mediaId)
        return IndexedInfo(
            place = indexing.geo(mediaId)?.let { placeLabel(it) },
            text = indexing.text(mediaId),
            people = faces.mapNotNull { it.name }.distinct(),
            unnamedFaces = faces.count { it.name == null },
        )
    }

    private suspend fun placeLabel(geo: MediaGeoEntity): String? {
        val country = geo.countryCode?.let { Locale.Builder().setRegion(it).build().getDisplayCountry(context.resources.configuration.locales[0]) }
        val cityId = geo.cityId ?: return country?.takeIf { it.isNotBlank() }
        val gazetteer = gazetteers.get()
        val city = gazetteer.city(cityId) ?: return country
        val region = gazetteer.regionDisplayNames[city.regionKey]
        return listOfNotNull(city.name, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
    }
}
