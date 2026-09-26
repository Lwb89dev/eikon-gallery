package app.eikon.gallery.data.db.encryption

import app.eikon.gallery.data.backup.SecretRead
import app.eikon.gallery.data.backup.SecretStore
import java.security.SecureRandom

/**
 * The key of the library's database: 256 random bits. SQLCipher takes it as it is (a "raw key"), with no password stretching, because it is already random; the text form is
 * SQLite's blob literal, `x'…'`.
 */
class DatabaseKey private constructor(private val hex: String) {
    /** The key as SQLCipher wants it in `PRAGMA key` and in ATTACH: a blob literal. */
    fun sqlLiteral() = "x'$hex'"

    /** The same, as the bytes SQLCipher's helper takes. Each caller gets its own array because the helper wipes it after use. */
    fun passphrase(): ByteArray = sqlLiteral().toByteArray(Charsets.US_ASCII)

    // No toString(): a key must never end up in a log line by accident.
    fun stored() = hex

    companion object {
        private const val BYTES = 32

        fun generate(random: SecureRandom = SecureRandom()): DatabaseKey =
            DatabaseKey(ByteArray(BYTES).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) })

        /** The key stored as [text], or null if that is not a key. */
        fun parse(text: String): DatabaseKey? = text.takeIf { it.length == BYTES * 2 && it.all { c -> c in "0123456789abcdef" } }?.let(::DatabaseKey)
    }
}

/** What is known about the key at start-up. */
sealed interface KeyLookup {
    /** There has never been one: a first launch, or a database that has not been encrypted yet. */
    data object Absent : KeyLookup

    /** One was stored and cannot be read any more (the Keystore key is gone): whatever it protected cannot be opened. */
    data object Lost : KeyLookup

    data class Ready(val key: DatabaseKey) : KeyLookup
}

/** Keeps the database key in a [SecretStore] (on the phone, one sealed under the Android Keystore). */
class DatabaseKeys(private val secrets: SecretStore, private val random: SecureRandom = SecureRandom()) {
    /** A failure of the store itself is an exception and is not turned into [KeyLookup.Lost]: losing the database over a passing fault would be unforgivable. */
    fun lookup(): KeyLookup = when (val stored = secrets.read(NAME)) {
        SecretRead.Missing -> KeyLookup.Absent
        SecretRead.Unreadable -> KeyLookup.Lost
        is SecretRead.Value -> DatabaseKey.parse(stored.value)?.let { KeyLookup.Ready(it) } ?: KeyLookup.Lost
    }

    /** Makes a key and stores it. Called before anything is encrypted with it, so no database can exist whose key was never saved. */
    fun create(): DatabaseKey = DatabaseKey.generate(random).also { secrets.put(NAME, it.stored()) }

    private companion object {
        const val NAME = "database-key"
    }
}
