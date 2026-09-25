package app.eikon.gallery.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.eikon.gallery.data.db.EikonDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A phone that has the released version 1 database must keep its media index when the app updates.
 *
 * The old database is rebuilt from the schema Room exported for version 1 (tables, indices and Room's
 * own identity record), opened with the current [EikonDatabase], and Room runs the real automatic
 * migration and then validates the result against the version 2 schema. If the migration were wrong
 * or missing, opening would throw instead of returning data.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun removeLeftovers() {
        context.deleteDatabase(NAME)
    }

    @After
    fun cleanUp() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun migratingFromVersion1KeepsMediaAndMakesTheNewTablesUsable() = runTest {
        createVersion1Database()

        val db = Room.databaseBuilder(context, EikonDatabase::class.java, NAME)
            .addMigrations(*app.eikon.gallery.data.db.DatabaseMigrations.ALL)
            .build()
        try {
            assertEquals(listOf(7L), db.mediaDao().allIds())
            val albumId = db.albumDao().create("Trip", 0)
            db.albumDao().addItems(listOf(app.eikon.gallery.data.db.AlbumItemEntity(albumId, 7, 0)))
            db.hiddenDao().hide(listOf(app.eikon.gallery.data.db.HiddenMediaEntity(7, 0)))
            assertEquals(1, db.albumDao().observeAlbums().first().size)
            db.indexDao().insertSearch(listOf(app.eikon.gallery.data.db.MediaSearchEntity(7, "a jpg", "")))
            assertEquals(listOf(7L), db.indexDao().existingSearchRows(listOf(7L)))
        } finally {
            db.close()
        }
    }

    private fun createVersion1Database() {
        val schemaText = InstrumentationRegistry.getInstrumentation().context.assets
            .open("app.eikon.gallery.data.db.EikonDatabase/1.json").bufferedReader().use { it.readText() }
        val database = JSONObject(schemaText).getJSONObject("database")
        val path = context.getDatabasePath(NAME)
        path.parentFile?.mkdirs()
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        try {
            val entities = database.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    raw.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = database.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            raw.execSQL(
                "INSERT INTO media (id, displayName, mimeType, isVideo, takenAt, addedAt, modifiedAt, width, height, durationMs, " +
                    "sizeBytes, relativePath, bucketName, isFavorite, isScreenshot, isScreenRecording, isPanorama, isRaw) " +
                    "VALUES (7, 'a.jpg', 'image/jpeg', 0, 1, 1, 1, 10, 10, 0, 5, 'DCIM/Camera/', 'Camera', 1, 0, 0, 0, 0)",
            )
            raw.version = 1
        } finally {
            raw.close()
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
