package app.eikon.gallery.data.db.encryption

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * What Room is given to open the library's database: it makes sure the database is encrypted (see [DatabaseEncryptor]) the first time it is really opened, and hands [onOpened] how that ended.
 */
class EncryptedOpenHelperFactory(
    private val context: Context,
    private val keys: DatabaseKeys,
    private val names: DatabaseNames,
    private val onOpened: (Opened<*>) -> Unit,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        DeferredOpenHelper(configuration.name) {
            val directory = context.getDatabasePath(names.plain).parentFile!!
            val opened = DatabaseEncryptor(directory, keys, SqlCipherEngine(context, configuration), names).open()
            onOpened(opened)
            opened.database
        }
}
