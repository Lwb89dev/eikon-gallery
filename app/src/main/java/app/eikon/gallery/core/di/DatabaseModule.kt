package app.eikon.gallery.core.di

import android.content.Context
import androidx.room.Room
import app.eikon.gallery.data.db.AlbumDao
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.db.HiddenDao
import app.eikon.gallery.data.db.MediaDao
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
        Room.databaseBuilder(context, EikonDatabase::class.java, "eikon.db").build()

    @Provides
    fun mediaDao(database: EikonDatabase): MediaDao = database.mediaDao()

    @Provides
    fun albumDao(database: EikonDatabase): AlbumDao = database.albumDao()

    @Provides
    fun hiddenDao(database: EikonDatabase): HiddenDao = database.hiddenDao()
}
