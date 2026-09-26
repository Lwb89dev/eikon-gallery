package app.eikon.gallery.data.db.encryption

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.eikon.gallery.data.db.DatabaseMigrations
import app.eikon.gallery.data.db.EikonDatabase
import net.zetetic.database.LogTarget
import net.zetetic.database.Logger
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * [DatabaseEngine] on the phone: Room for the schema, SQLCipher for the encryption. [configuration] is the one Room built the app's database with, so that the database in use
 * has the same callback (and with it the same migrations and validation) as any Room database would.
 */
class SqlCipherEngine(
    private val context: Context,
    private val configuration: SupportSQLiteOpenHelper.Configuration,
) : DatabaseEngine<SupportSQLiteOpenHelper> {
    override fun createEncrypted(name: String, key: DatabaseKey) {
        loadLibrary()
        // TRUNCATE rather than WAL: the file must be complete on its own, with nothing left in a side file, when it is renamed into place.
        openAndClose(encryptedRoom(name, key).setJournalMode(RoomDatabase.JournalMode.TRUNCATE))
    }

    override fun upgradePlain(name: String) = openAndClose(Room.databaseBuilder(context, EikonDatabase::class.java, name).addMigrations(*DatabaseMigrations.ALL))

    override fun copy(plain: String, encrypted: String, key: DatabaseKey) {
        loadLibrary()
        // An empty password means "not encrypted": this is how SQLCipher reads an ordinary database.
        val database = SQLiteDatabase.openDatabase(context.getDatabasePath(plain).path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, null)
        try {
            DatabaseCopy(SqlCipherSession(database)).copyTo(context.getDatabasePath(encrypted).path, " KEY ${quoteText(key.sqlLiteral())}")
        } finally {
            database.close()
        }
    }

    override fun verify(name: String, key: DatabaseKey) {
        loadLibrary()
        openAndClose(encryptedRoom(name, key))
    }

    override fun useEncrypted(name: String, key: DatabaseKey): SupportSQLiteOpenHelper {
        loadLibrary()
        return SupportOpenHelperFactory(key.passphrase()).create(configurationFor(name))
    }

    override fun usePlain(name: String): SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(configurationFor(name))

    private fun encryptedRoom(name: String, key: DatabaseKey) =
        Room.databaseBuilder(context, EikonDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key.passphrase()))
            .addMigrations(*DatabaseMigrations.ALL)

    /** Opening is what makes Room build the tables, migrate them and check them against the schema. */
    private fun openAndClose(builder: RoomDatabase.Builder<EikonDatabase>) {
        val database = builder.build()
        try {
            database.openHelper.writableDatabase
        } finally {
            database.close()
        }
    }

    private fun configurationFor(name: String) = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
        .name(name)
        .callback(configuration.callback)
        .noBackupDirectory(configuration.useNoBackupDirectory)
        .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
        .build()

    private fun loadLibrary() {
        try {
            System.loadLibrary("sqlcipher")
        } catch (e: UnsatisfiedLinkError) {
            // An Error would not be caught by the code that falls back to a readable database.
            throw IllegalStateException("SQLCipher could not be loaded", e)
        }
        Logger.setTarget(Silent)
    }

    /** The library would log some of what it does; nothing about the database belongs in a log. */
    private object Silent : LogTarget {
        override fun isLoggable(tag: String, priority: Int) = false

        override fun log(priority: Int, tag: String, message: String, throwable: Throwable?) = Unit
    }
}

/** [SqlSession] on a SQLCipher connection. It has a single connection (write-ahead logging is not switched on for it), so what ATTACH does is seen by every statement after it. */
private class SqlCipherSession(private val database: SQLiteDatabase) : SqlSession {
    override fun execute(sql: String) = database.execSQL(sql)

    override fun strings(sql: String): List<String> = database.rawQuery(sql, emptyArray<String>()).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    override fun <T> transaction(block: () -> T): T {
        database.beginTransaction()
        try {
            return block().also { database.setTransactionSuccessful() }
        } finally {
            database.endTransaction()
        }
    }
}
