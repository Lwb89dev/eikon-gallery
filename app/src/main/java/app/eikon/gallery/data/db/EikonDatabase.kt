package app.eikon.gallery.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Application database.
 *
 * - `media`: rebuildable cache of MediaStore metadata (see [MediaEntity]).
 * - `album`, `album_item`, `hidden_media`: user-authored data. They are never derived from MediaStore
 *   and never touched when the media cache is cleared or re-synced.
 * - `index_state`, `media_geo`, `media_search`: what the background analysis has learned about each
 *   photo (places, text). Derived data: it is rebuilt if lost, and cleared with the media cache.
 *
 * Schema changes go through real migrations (auto-migrations where Room can derive them); a
 * destructive fallback is never configured because user data lives here.
 */
@Database(
    entities = [
        MediaEntity::class,
        AlbumEntity::class,
        AlbumItemEntity::class,
        HiddenMediaEntity::class,
        IndexStateEntity::class,
        MediaGeoEntity::class,
        MediaSearchEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class EikonDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun albumDao(): AlbumDao
    abstract fun hiddenDao(): HiddenDao
    abstract fun indexDao(): IndexDao
}
