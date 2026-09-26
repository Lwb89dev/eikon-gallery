package app.eikon.gallery.core.di

import android.content.Context
import androidx.room.Room
import app.eikon.gallery.data.db.AlbumDao
import app.eikon.gallery.data.backup.KeystoreSecretStore
import app.eikon.gallery.data.db.DatabaseMigrations
import app.eikon.gallery.data.db.DuplicatesDao
import app.eikon.gallery.data.db.BackupDao
import app.eikon.gallery.data.db.EditDao
import app.eikon.gallery.data.db.MetadataDao
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.db.encryption.DatabaseKeys
import app.eikon.gallery.data.db.encryption.DatabaseNames
import app.eikon.gallery.data.db.encryption.DatabaseProtectionState
import app.eikon.gallery.data.db.encryption.EncryptedOpenHelperFactory
import app.eikon.gallery.data.db.HiddenDao
import app.eikon.gallery.data.db.IndexDao
import app.eikon.gallery.data.db.MediaDao
import app.eikon.gallery.data.db.MemoryDao
import app.eikon.gallery.data.db.PeopleDao
import app.eikon.gallery.data.db.PlacesDao
import app.eikon.gallery.data.db.RoomTransactor
import app.eikon.gallery.data.db.Transactor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** The key of the database, sealed under a key of the Android Keystore. */
    @Provides
    @Singleton
    fun databaseKeys(@ApplicationContext context: Context): DatabaseKeys = DatabaseKeys(KeystoreSecretStore.database(context))

    /** The database is encrypted when it is first opened (see [EncryptedOpenHelperFactory]). The name it is built with is the one from before the encryption. */
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context, keys: DatabaseKeys, state: DatabaseProtectionState): EikonDatabase =
        Room.databaseBuilder(context, EikonDatabase::class.java, DatabaseNames.DEFAULT.plain)
            .openHelperFactory(EncryptedOpenHelperFactory(context, keys, DatabaseNames.DEFAULT, state::report))
            .addMigrations(*DatabaseMigrations.ALL)
            .build()

    @Provides
    fun transactor(impl: RoomTransactor): Transactor = impl

    @Provides
    fun mediaDao(database: EikonDatabase): MediaDao = database.mediaDao()

    @Provides
    fun albumDao(database: EikonDatabase): AlbumDao = database.albumDao()

    @Provides
    fun hiddenDao(database: EikonDatabase): HiddenDao = database.hiddenDao()

    @Provides
    fun indexDao(database: EikonDatabase): IndexDao = database.indexDao()

    @Provides
    fun peopleDao(database: EikonDatabase): PeopleDao = database.peopleDao()

    @Provides
    fun placesDao(database: EikonDatabase): PlacesDao = database.placesDao()

    @Provides
    fun duplicatesDao(database: EikonDatabase): DuplicatesDao = database.duplicatesDao()

    @Provides
    fun memoryDao(database: EikonDatabase): MemoryDao = database.memoryDao()

    @Provides
    fun editDao(database: EikonDatabase): EditDao = database.editDao()

    @Provides
    fun backupDao(database: EikonDatabase): BackupDao = database.backupDao()

    @Provides
    fun metadataDao(database: EikonDatabase): MetadataDao = database.metadataDao()
}
