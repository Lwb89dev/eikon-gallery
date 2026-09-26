package app.eikon.gallery.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.mediastore.ContentResolverMediaStoreSource
import app.eikon.gallery.data.sync.DataStoreSyncStateStore
import app.eikon.gallery.data.sync.MediaIndex
import app.eikon.gallery.data.sync.MediaStoreSource
import app.eikon.gallery.data.sync.RoomMediaIndex
import app.eikon.gallery.data.sync.SyncStateStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds
    abstract fun mediaStoreSource(impl: ContentResolverMediaStoreSource): MediaStoreSource

    @Binds
    abstract fun mediaIndex(impl: RoomMediaIndex): MediaIndex

    @Binds
    abstract fun syncStateStore(impl: DataStoreSyncStateStore): SyncStateStore
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    fun clock(): Clock = Clock { System.currentTimeMillis() }

    /** A file that cannot be read (it was cut short, the phone lost power) is replaced by an empty one: settings go back to their defaults, which is better than a screen that never opens. */
    private fun stores(context: Context, name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    ) { context.preferencesDataStoreFile(name) }

    @Provides
    @Singleton
    @SettingsStore
    fun settingsStore(@ApplicationContext context: Context): DataStore<Preferences> =
        stores(context, "settings")

    @Provides
    @Singleton
    @BackupStore
    fun backupStore(@ApplicationContext context: Context): DataStore<Preferences> =
        stores(context, "backup")

    @Provides
    @Singleton
    @SyncStore
    fun syncStore(@ApplicationContext context: Context): DataStore<Preferences> =
        stores(context, "sync_state")
}
