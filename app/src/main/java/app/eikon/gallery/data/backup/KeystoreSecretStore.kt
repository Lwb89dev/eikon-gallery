package app.eikon.gallery.data.backup

import android.content.Context
import androidx.core.content.edit
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Keeps secrets encrypted (see [SecretBox]) under a key made and held by the Android Keystore: the key cannot be read out of it, not even by this app,
 * so the stored text is useless anywhere but on this phone, in this app. It is in a private file, excluded from every backup, and is deleted with the app.
 * Each use has its own file and its own Keystore key ([backup] for the credential of the backup, [database] for the key of the library's database), so one cannot open the other's.
 */
class KeystoreSecretStore(context: Context, file: String, private val alias: String) : SecretStore {
    private val preferences = context.getSharedPreferences(file, Context.MODE_PRIVATE)
    private val box = SecretBox { key() }

    override fun get(name: String): String? = preferences.getString(name, null)?.let { box.open(name, it) }

    override fun has(name: String): Boolean = preferences.contains(name)

    override fun read(name: String): SecretRead {
        val sealed = preferences.getString(name, null) ?: return SecretRead.Missing
        return when (val opened = box.unseal(name, sealed)) {
            is Unsealed.Secret -> SecretRead.Value(opened.value)
            Unsealed.Rejected -> SecretRead.Unreadable
        }
    }

    override fun seal(name: String, value: String): String = box.seal(name, value)

    override fun unseal(name: String, sealed: String): String? = box.open(name, sealed)

    override fun put(name: String, value: String) = preferences.edit(commit = true) { putString(name, box.seal(name, value)) }

    override fun remove(name: String) = preferences.edit(commit = true) { remove(name) }

    // Two threads must not both make the key: the second would replace the first, and what was sealed with the first could never be opened.
    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val KEY_BITS = 256

        /** The credential of the backup (names kept from the first release, so a credential already stored still opens). */
        fun backup(context: Context) = KeystoreSecretStore(context, "backup_secrets", "eikon-backup-credential")

        /** The key of the library's database. Written synchronously: a key that was made but not stored would leave a database nobody can open. */
        fun database(context: Context) = KeystoreSecretStore(context, "database_secrets", "eikon-database-key")
    }
}
