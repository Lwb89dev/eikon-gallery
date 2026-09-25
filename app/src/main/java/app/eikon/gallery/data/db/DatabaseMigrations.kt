package app.eikon.gallery.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Manual migrations. Version 1 to 2 is an automatic migration declared on the database; the ones here
 * involve tables Room cannot derive (virtual tables) or are simply written out so the SQL is reviewed.
 *
 * The statements are the exact `createSql` Room exports in `app/schemas/.../3.json`; a JVM test
 * applies them to a real version 2 database and checks they still equal that export, so a schema change
 * cannot silently drift from its migration.
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

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3)
}
