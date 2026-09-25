package app.eikon.gallery.core.di

import android.content.Context
import androidx.room.Room
import app.eikon.gallery.data.db.AlbumDao
import app.eikon.gallery.data.db.DatabaseMigrations
import app.eikon.gallery.data.db.DuplicatesDao
import app.eikon.gallery.data.db.EditDao
import app.eikon.gallery.data.db.EikonDatabase
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
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): EikonDatabase =
        Room.databaseBuilder(context, EikonDatabase::class.java, "eikon.db")
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
}
