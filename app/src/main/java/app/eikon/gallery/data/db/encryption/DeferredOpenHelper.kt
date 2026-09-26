package app.eikon.gallery.data.db.encryption

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * A database helper that decides which database to open, and does the encryption, only when Room first needs the database: [create] runs on that first call.
 *
 * Room asks for the helper when the app is built, on the main thread, but only opens it (on a background thread) at the first query. Converting a big library there is slow, so it must
 * wait for that moment; whoever else asks for the database meanwhile waits for it.
 */
class DeferredOpenHelper(
    override val databaseName: String?,
    private val create: () -> SupportSQLiteOpenHelper,
) : SupportSQLiteOpenHelper {
    private var delegate: SupportSQLiteOpenHelper? = null
    private var walEnabled: Boolean? = null

    @Synchronized
    private fun helper(): SupportSQLiteOpenHelper = delegate ?: create().also { made ->
        walEnabled?.let(made::setWriteAheadLoggingEnabled)
        delegate = made
    }

    @Synchronized
    override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
        walEnabled = enabled
        delegate?.setWriteAheadLoggingEnabled(enabled)
    }

    override val writableDatabase: SupportSQLiteDatabase get() = helper().writableDatabase

    override val readableDatabase: SupportSQLiteDatabase get() = helper().readableDatabase

    @Synchronized
    override fun close() {
        delegate?.close()
    }
}
