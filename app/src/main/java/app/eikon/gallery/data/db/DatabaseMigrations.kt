package app.eikon.gallery.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Manual migrations. Version 1 to 2 is an automatic migration declared on the database; the ones here
 * involve tables Room cannot derive (virtual tables) or are simply written out so the SQL is reviewed.
 *
 * The statements are the exact `createSql` Room exports in `app/schemas/.../<version>.json`; a JVM test
 * applies them to a real database of the previous version and checks they still equal that export, so a
 * schema change cannot silently drift from its migration.
 */
object DatabaseMigrations {
    /** Adds the analysis tables: per-photo state, resolved places and the full-text index. */
    val STATEMENTS_2_3: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `index_state` (`mediaId` INTEGER NOT NULL, `stage` TEXT NOT NULL, " +
            "`status` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`mediaId`, `stage`))",
        "CREATE INDEX IF NOT EXISTS `index_index_state_stage_status` ON `index_state` (`stage`, `status`)",
        "CREATE TABLE IF NOT EXISTS `media_geo` (`mediaId` INTEGER NOT NULL, `latitude` REAL NOT NULL, " +
            "`longitude` REAL NOT NULL, `cityId` INTEGER, `countryCode` TEXT, `regionKey` TEXT, PRIMARY KEY(`mediaId`))",
        "CREATE INDEX IF NOT EXISTS `index_media_geo_cityId` ON `media_geo` (`cityId`)",
        "CREATE INDEX IF NOT EXISTS `index_media_geo_countryCode` ON `media_geo` (`countryCode`)",
        "CREATE INDEX IF NOT EXISTS `index_media_geo_regionKey` ON `media_geo` (`regionKey`)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS `media_search` USING FTS4(`filename` TEXT NOT NULL, `ocr` TEXT NOT NULL, " +
            "tokenize=unicode61 `remove_diacritics=2`)",
    )

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STATEMENTS_2_3.forEach(db::execSQL)
        }
    }

    /**
     * Adds the image embeddings, the scratch table holding the hits of the current semantic search, and the faces
     * and people.
     */
    val STATEMENTS_3_4: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `media_embedding` (`mediaId` INTEGER NOT NULL, `model` TEXT NOT NULL, " +
            "`vector` BLOB NOT NULL, PRIMARY KEY(`mediaId`))",
        "CREATE TABLE IF NOT EXISTS `search_hit` (`queryId` INTEGER NOT NULL, `mediaId` INTEGER NOT NULL, " +
            "`score` REAL NOT NULL, PRIMARY KEY(`queryId`, `mediaId`))",
        "CREATE TABLE IF NOT EXISTS `person` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT, " +
            "`isFavorite` INTEGER NOT NULL, `isHidden` INTEGER NOT NULL, `isPinned` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `face` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mediaId` INTEGER NOT NULL, " +
            "`left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, `score` REAL NOT NULL, " +
            "`vector` BLOB NOT NULL, `personId` INTEGER, `ignored` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_face_mediaId` ON `face` (`mediaId`)",
        "CREATE INDEX IF NOT EXISTS `index_face_personId` ON `face` (`personId`)",
    )

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STATEMENTS_3_4.forEach(db::execSQL)
        }
    }

    /** Adds file and picture fingerprints for duplicates, the dismissed duplicate groups, and the user's choices for memories. */
    val STATEMENTS_4_5: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `content_hash` (`mediaId` INTEGER NOT NULL, `hash` TEXT NOT NULL, `modifiedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))",
        "CREATE TABLE IF NOT EXISTS `perceptual_hash` (`mediaId` INTEGER NOT NULL, `hash` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))",
        "CREATE TABLE IF NOT EXISTS `duplicate_dismissed` (`key` TEXT NOT NULL, `dismissedAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
        "CREATE TABLE IF NOT EXISTS `memory_preference` (`key` TEXT NOT NULL, `value` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`key`))",
    )

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STATEMENTS_4_5.forEach(db::execSQL)
        }
    }

    /** Adds the table of edits (non-destructive: one recipe per edited photo). */
    val STATEMENTS_5_6: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `edit_recipe` (`mediaId` INTEGER NOT NULL, `recipe` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `baseModifiedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))",
    )

    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STATEMENTS_5_6.forEach(db::execSQL)
        }
    }

    /** Two indexes so that a device folder is read straight from the index, in date order, however big the library around it is. */
    val STATEMENTS_6_7: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `index_media_relativePath_takenAt` ON `media` (`relativePath`, `takenAt`)",
        "CREATE INDEX IF NOT EXISTS `index_media_relativePath_addedAt` ON `media` (`relativePath`, `addedAt`)",
    )

    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STATEMENTS_6_7.forEach(db::execSQL)
        }
    }

    /** Adds the bookkeeping of the backup to a server (which photos were sent). Empty and unused unless the user turns the backup on. */
    val STATEMENTS_7_8: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `backup_item` (`mediaId` INTEGER NOT NULL, `status` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `modifiedAt` INTEGER NOT NULL, `sizeBytes` INTEGER NOT NULL, `checksum` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`))",
    )

    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STATEMENTS_7_8.forEach(db::execSQL)
        }
    }

    /** Captions (searchable), what a photo's date and location were before eikon changed them, and an index for finding files of the same size. */
    val STATEMENTS_8_9: List<String> = listOf(
        "CREATE VIRTUAL TABLE IF NOT EXISTS `media_caption` USING FTS4(`caption` TEXT NOT NULL, tokenize=unicode61 `remove_diacritics=2`)",
        "CREATE TABLE IF NOT EXISTS `metadata_original` (`mediaId` INTEGER NOT NULL, `field` TEXT NOT NULL, `value` TEXT, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`mediaId`, `field`))",
        "CREATE INDEX IF NOT EXISTS `index_media_sizeBytes_isVideo` ON `media` (`sizeBytes`, `isVideo`)",
    )

    val MIGRATION_8_9: Migration = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            STATEMENTS_8_9.forEach(db::execSQL)
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
}
