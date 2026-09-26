package app.eikon.gallery.data.db.encryption

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The database engine underneath: everything [DatabaseEncryptor] needs from Room and SQLCipher, so the rules around it can be tested with files alone. */
interface DatabaseEngine<T> {
    /** Makes an empty encrypted database called [name], with the app's tables, and closes it. */
    fun createEncrypted(name: String, key: DatabaseKey)

    /** Opens the readable database [name] once, so that Room brings it to the newest schema, and closes it. */
    fun upgradePlain(name: String)

    /** Copies everything from the readable database [plain] into the encrypted [encrypted] (see [DatabaseCopy]). */
    fun copy(plain: String, encrypted: String, key: DatabaseKey)

    /** Opens the encrypted database [name] as the app would, and fails if it cannot be read or does not fit the schema. */
    fun verify(name: String, key: DatabaseKey)

    fun useEncrypted(name: String, key: DatabaseKey): T

    fun usePlain(name: String): T
}

enum class Protection {
    /** The library's database is encrypted. */
    ENCRYPTED,

    /** It is not: the encryption failed, so the app went on with a readable database and will try again the next time it starts. */
    NOT_ENCRYPTED,

    /** It is encrypted, but its key was lost, so the earlier content could not be opened and a new database was started. */
    RESET,
}

/** [failure] is the kind of error (its class name, never its message, which could name a file) when [protection] is [Protection.NOT_ENCRYPTED]. */
class Opened<T>(val database: T, val protection: Protection, val failure: String? = null)

/** The file names of one library: the readable database of before the encryption, the encrypted one, and the encrypted one while it is being made. Tests use names of their own. */
class DatabaseNames(prefix: String) {
    val plain = "$prefix.db"
    val encrypted = "$prefix-encrypted.db"
    val staged = "$prefix-encrypted.db.tmp"

    companion object {
        val DEFAULT = DatabaseNames("eikon")
    }
}

/**
 * Makes sure the library's database is encrypted, and hands the engine the right one to open.
 *
 * - **First launch**: an encrypted database is made.
 * - **An older, readable database**: it is brought to the newest schema, copied into an encrypted one, and only when that copy has been checked, the readable one is overwritten and
 *   deleted. The order matters: the key is stored first, the copy is made under another name and renamed into place in one step, so whichever moment the app is stopped at, one of the two
 *   databases is complete and the next start picks up from there.
 * - **Anything goes wrong while encrypting**: nothing is deleted, the app works with what it had (readable) and says so, and tries again the next time.
 * - **The key is gone** (only possible if the Keystore lost it): the encrypted file cannot be opened by anyone; it is discarded and a new one is made, and the user is told.
 */
class DatabaseEncryptor<T>(
    private val directory: File,
    private val keys: DatabaseKeys,
    private val engine: DatabaseEngine<T>,
    private val names: DatabaseNames = DatabaseNames.DEFAULT,
) {
    private val plain = File(directory, names.plain)
    private val encrypted = File(directory, names.encrypted)
    private val staged = File(directory, names.staged)

    fun open(): Opened<T> {
        val lookup = keys.lookup()
        val plan = EncryptionPlan.decide(plain.exists(), encrypted.exists(), lookup)
        if (plan.discardEncrypted) SecureFiles.wipe(SecureFiles.family(encrypted))
        if (plan.action == EncryptionPlan.Action.OPEN) return reopen(plan, lookup)
        return try {
            val key = (lookup as? KeyLookup.Ready)?.key ?: keys.create()
            if (plan.action == EncryptionPlan.Action.CONVERT) convert(key) else create(key)
            Opened(engine.useEncrypted(names.encrypted, key), if (plan.keyLost) Protection.RESET else Protection.ENCRYPTED)
        } catch (e: Exception) {
            SecureFiles.wipe(SecureFiles.family(staged))
            Opened(engine.usePlain(names.plain), Protection.NOT_ENCRYPTED, e.javaClass.simpleName)
        }
    }

    private fun reopen(plan: EncryptionPlan, lookup: KeyLookup): Opened<T> {
        // A readable copy left behind by an interrupted conversion: the encrypted one is complete (it was renamed only after it was checked).
        if (plan.discardPlaintext) SecureFiles.wipe(SecureFiles.family(plain))
        return Opened(engine.useEncrypted(names.encrypted, (lookup as KeyLookup.Ready).key), Protection.ENCRYPTED)
    }

    private fun create(key: DatabaseKey) {
        SecureFiles.wipe(SecureFiles.family(staged))
        engine.createEncrypted(names.staged, key)
        promote()
        verifyOrDiscard(key)
    }

    private fun convert(key: DatabaseKey) {
        SecureFiles.wipe(SecureFiles.family(staged))
        engine.upgradePlain(names.plain)
        engine.createEncrypted(names.staged, key)
        engine.copy(names.plain, names.staged, key)
        promote()
        verifyOrDiscard(key)
        // If this fails, the next start sees both files and deletes the readable one then.
        SecureFiles.wipe(SecureFiles.family(plain))
    }

    private fun verifyOrDiscard(key: DatabaseKey) {
        try {
            engine.verify(names.encrypted, key)
        } catch (e: Exception) {
            // Not a database the app can use: it must not stay, or the next start would trust it and delete the readable one.
            SecureFiles.wipe(SecureFiles.family(encrypted))
            throw e
        }
    }

    /**
     * The staged database becomes *the* database in one step. It must be closed by now, or part of it would still be in a side file: an empty one is only the journal
     * SQLite leaves behind in its TRUNCATE mode.
     */
    private fun promote() {
        val unfinished = SecureFiles.family(staged).drop(1).filter { it.length() > 0 }
        check(unfinished.isEmpty()) { "the staged database was not closed cleanly" }
        Files.move(staged.toPath(), encrypted.toPath(), StandardCopyOption.ATOMIC_MOVE)
        SecureFiles.wipe(SecureFiles.family(staged))
    }
}
