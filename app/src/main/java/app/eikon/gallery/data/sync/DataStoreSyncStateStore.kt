package app.eikon.gallery.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.eikon.gallery.core.di.SyncStore
import javax.inject.Inject
import kotlinx.coroutines.flow.first

class DataStoreSyncStateStore @Inject constructor(
    @SyncStore private val store: DataStore<Preferences>,
) : SyncStateStore {
    override suspend fun read(): SyncState {
        val prefs = store.data.first()
        return SyncState(
            generation = prefs[GENERATION] ?: SyncState.NO_GENERATION,
            version = prefs[VERSION],
            accessStamp = prefs[ACCESS_STAMP],
        )
    }

    override suspend fun write(state: SyncState) {
        store.edit { prefs ->
            prefs[GENERATION] = state.generation
            state.version?.let { prefs[VERSION] = it } ?: prefs.remove(VERSION)
            state.accessStamp?.let { prefs[ACCESS_STAMP] = it } ?: prefs.remove(ACCESS_STAMP)
        }
    }

    private companion object {
        val GENERATION = longPreferencesKey("generation")
        val VERSION = stringPreferencesKey("media_store_version")
        val ACCESS_STAMP = stringPreferencesKey("access_stamp")
    }
}
