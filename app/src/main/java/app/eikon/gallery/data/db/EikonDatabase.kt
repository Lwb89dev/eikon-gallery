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
 * - `index_state`, `media_geo`, `media_search`, `media_embedding`: what the background analysis has
 *   learned about each photo (places, text, what it looks like). Derived data: it is rebuilt if lost,
 *   and cleared with the media cache.
 * - `search_hit`: scratch table holding the photos that matched the current semantic search.
 * - `content_hash`, `perceptual_hash`: fingerprints of files and pictures, to find duplicates. Derived data.
 * - `duplicate_dismissed`, `memory_preference`: what the user said about duplicate groups and memories. User data: kept
 *   when the media cache is cleared.
 * - `edit_recipe`: the edit of a photo, as text. User data, kept when the media cache is cleared: it cannot be rebuilt.
 * - `face`, `person`: faces found in photos and the people they were grouped into. The faces are derived data
 *   (rebuilt by the analysis, cleared with the media cache); a person is what the user makes of them: named,
 *   merged, hidden. Both go when photo access is revoked.
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
        MediaEmbeddingEntity::class,
        SearchHitEntity::class,
        PersonEntity::class,
        FaceEntity::class,
        ContentHashEntity::class,
        PerceptualHashEntity::class,
        DuplicateDismissalEntity::class,
        MemoryPreferenceEntity::class,
        EditRecipeEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class EikonDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun albumDao(): AlbumDao
    abstract fun hiddenDao(): HiddenDao
    abstract fun indexDao(): IndexDao
    abstract fun peopleDao(): PeopleDao
    abstract fun placesDao(): PlacesDao
    abstract fun duplicatesDao(): DuplicatesDao
    abstract fun memoryDao(): MemoryDao
    abstract fun editDao(): EditDao
}
