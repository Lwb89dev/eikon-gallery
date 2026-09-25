package app.eikon.gallery.data.places

import android.content.Context
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads the bundled place data on first use (about 2.5 MB, well under a second) and keeps it for the
 * life of the process. Nothing is read from the network; the files are assets inside the APK.
 */
@Singleton
class GazetteerProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lock = Mutex()
    private var loaded: Gazetteer? = null

    suspend fun get(): Gazetteer = lock.withLock {
        loaded ?: withContext(Dispatchers.Default) { load() }.also { loaded = it }
    }

    private fun load(): Gazetteer {
        val locales = listOf(currentLocale(), Locale.ENGLISH).distinct()
        return Gazetteer.build(
            cityLines = readLines(CITIES).asSequence(),
            regionLines = readLines(REGIONS).asSequence(),
            aliasLines = readLines(ALIASES).asSequence(),
            countryLocales = locales,
        )
    }

    private fun readLines(path: String): List<String> =
        context.assets.open(path).bufferedReader().use { it.readLines() }

    private fun currentLocale(): Locale {
        val configuration: Configuration = context.resources.configuration
        return configuration.locales[0]
    }

    private companion object {
        const val CITIES = "places/cities.tsv"
        const val REGIONS = "places/regions.tsv"
        const val ALIASES = "places/aliases.tsv"
    }
}
