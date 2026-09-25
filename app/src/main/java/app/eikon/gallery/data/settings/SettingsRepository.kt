package app.eikon.gallery.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.eikon.gallery.core.di.ApplicationScope
import app.eikon.gallery.core.di.SettingsStore
import app.eikon.gallery.domain.CategoryFilter
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.SortDirection
import app.eikon.gallery.domain.SortField
import app.eikon.gallery.domain.TypeFilter
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * What the background analysis may do. Everything it learns (places, text found in photos, what
 * photos show, faces) stays in eikon's private database on the phone. Every step is **off by default**: they
 * store where photos were taken, the text in them (receipts, documents...) and a description of their
 * content, which the user should choose to have recorded.
 */
data class AnalysisSettings(
    /** Stops all analysis until turned back on. */
    val paused: Boolean = false,
    /** Run only while the phone is charging (always also requires the battery not to be low). */
    val onlyWhileCharging: Boolean = true,
    /** Work out where photos were taken, to search by place. Needs the photo-location permission. */
    val places: Boolean = false,
    /** Read text inside photos, to search by it. The heaviest step. */
    val text: Boolean = false,
    /** Work out what each photo shows, to search by it ("dog", "sunset"). */
    val semantic: Boolean = false,
    /** Find the faces in photos and group them into people. Face data never leaves the phone. */
    val people: Boolean = false,
    /** Fingerprint photos and files so exact and near-identical copies can be found. No model involved. */
    val duplicates: Boolean = false,
) {
    val anyEnabled: Boolean get() = places || text || semantic || people || duplicates
}

/**
 * Everything that must survive closing the app: look, the library view the user left, and the privacy
 * options. [filters] and the sort apply to the main Library; collections have their own fixed scope.
 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val gridColumns: Int = DEFAULT_COLUMNS,
    val filters: LibraryFilters = LibraryFilters.NONE,
    val sortField: SortField = SortField.DATE_TAKEN,
    val direction: SortDirection = SortDirection.NEWEST_FIRST,
    /** Ask for fingerprint/face/PIN before opening Hidden. On by default. */
    val lockHidden: Boolean = true,
    /** Same for Recently deleted. Off by default. */
    val lockTrash: Boolean = false,
    /** Show the Hidden entry in Collections at all. */
    val showHidden: Boolean = true,
    val analysis: AnalysisSettings = AnalysisSettings(),
) {
    /** The main library: everything not hidden, with the saved filters and order. */
    val libraryQuery: LibraryQuery
        get() = LibraryQuery(LibraryScope.Everything, filters, sortField, direction)

    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 7
        const val DEFAULT_COLUMNS = 4
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @SettingsStore private val store: DataStore<Preferences>,
    @ApplicationScope scope: CoroutineScope,
) {
    private val settings: Flow<AppSettings> = store.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map(::toSettings)
        .distinctUntilChanged()

    /** Null only until the first read of the DataStore completes (the splash screen waits for it). */
    val state: StateFlow<AppSettings?> = settings.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME] = mode.name }
    }

    suspend fun setGridColumns(columns: Int) {
        store.edit { it[COLUMNS] = columns.coerceIn(AppSettings.MIN_COLUMNS, AppSettings.MAX_COLUMNS) }
    }

    suspend fun setFilters(filters: LibraryFilters) {
        store.edit {
            it[FILTER_TYPE] = filters.type.name
            it[FILTER_FAVORITES] = filters.favoritesOnly
            if (filters.category == null) it.remove(FILTER_CATEGORY) else it[FILTER_CATEGORY] = filters.category.name
        }
    }

    suspend fun setLockHidden(enabled: Boolean) {
        store.edit { it[LOCK_HIDDEN] = enabled }
    }

    suspend fun setLockTrash(enabled: Boolean) {
        store.edit { it[LOCK_TRASH] = enabled }
    }

    suspend fun setShowHidden(enabled: Boolean) {
        store.edit { it[SHOW_HIDDEN] = enabled }
    }

    suspend fun setAnalysis(transform: (AnalysisSettings) -> AnalysisSettings) {
        val current = state.value?.analysis ?: AnalysisSettings()
        val next = transform(current)
        store.edit {
            it[ANALYSIS_PAUSED] = next.paused
            it[ANALYSIS_CHARGING] = next.onlyWhileCharging
            it[ANALYSIS_PLACES] = next.places
            it[ANALYSIS_TEXT] = next.text
            it[ANALYSIS_SEMANTIC] = next.semantic
            it[ANALYSIS_PEOPLE] = next.people
            it[ANALYSIS_DUPLICATES] = next.duplicates
        }
    }

    suspend fun setSort(field: SortField, direction: SortDirection) {
        store.edit {
            it[SORT_FIELD] = field.name
            it[SORT_DIRECTION] = direction.name
        }
    }

    private fun toSettings(prefs: Preferences): AppSettings = AppSettings(
        themeMode = enumOrDefault(prefs[THEME], ThemeMode.SYSTEM),
        gridColumns = (prefs[COLUMNS] ?: AppSettings.DEFAULT_COLUMNS)
            .coerceIn(AppSettings.MIN_COLUMNS, AppSettings.MAX_COLUMNS),
        filters = LibraryFilters(
            type = enumOrDefault(prefs[FILTER_TYPE], TypeFilter.ALL),
            favoritesOnly = prefs[FILTER_FAVORITES] ?: false,
            category = CategoryFilter.entries.firstOrNull { it.name == prefs[FILTER_CATEGORY] },
        ),
        sortField = enumOrDefault(prefs[SORT_FIELD], SortField.DATE_TAKEN),
        direction = enumOrDefault(prefs[SORT_DIRECTION], SortDirection.NEWEST_FIRST),
        lockHidden = prefs[LOCK_HIDDEN] ?: true,
        lockTrash = prefs[LOCK_TRASH] ?: false,
        showHidden = prefs[SHOW_HIDDEN] ?: true,
        analysis = AnalysisSettings(
            paused = prefs[ANALYSIS_PAUSED] ?: false,
            onlyWhileCharging = prefs[ANALYSIS_CHARGING] ?: true,
            places = prefs[ANALYSIS_PLACES] ?: false,
            text = prefs[ANALYSIS_TEXT] ?: false,
            semantic = prefs[ANALYSIS_SEMANTIC] ?: false,
            people = prefs[ANALYSIS_PEOPLE] ?: false,
            duplicates = prefs[ANALYSIS_DUPLICATES] ?: false,
        ),
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        val THEME = stringPreferencesKey("theme_mode")
        val COLUMNS = intPreferencesKey("grid_columns")
        val FILTER_TYPE = stringPreferencesKey("filter_type")
        val FILTER_FAVORITES = booleanPreferencesKey("filter_favorites")
        val FILTER_CATEGORY = stringPreferencesKey("filter_category")
        val LOCK_HIDDEN = booleanPreferencesKey("lock_hidden")
        val LOCK_TRASH = booleanPreferencesKey("lock_trash")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val ANALYSIS_PAUSED = booleanPreferencesKey("analysis_paused")
        val ANALYSIS_CHARGING = booleanPreferencesKey("analysis_only_charging")
        val ANALYSIS_PLACES = booleanPreferencesKey("analysis_places")
        val ANALYSIS_TEXT = booleanPreferencesKey("analysis_text")
        val ANALYSIS_SEMANTIC = booleanPreferencesKey("analysis_semantic")
        val ANALYSIS_PEOPLE = booleanPreferencesKey("analysis_people")
        val ANALYSIS_DUPLICATES = booleanPreferencesKey("analysis_duplicates")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_DIRECTION = stringPreferencesKey("sort_direction")
    }
}
