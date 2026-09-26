package app.eikon.gallery.data.db.encryption

/** What to do about the library's database when the app starts, decided only from what is on disk and what is known about the key. */
data class EncryptionPlan(
    val action: Action,
    /** The encrypted file cannot be opened (its key is gone), so it is deleted first. */
    val discardEncrypted: Boolean = false,
    /** A readable copy is left over from before the encryption (the app was stopped after converting, before deleting it), and is deleted. */
    val discardPlaintext: Boolean = false,
    /** The key was lost: whatever was encrypted with it is gone, and the user is told. */
    val keyLost: Boolean = false,
) {
    enum class Action {
        /** Open the encrypted database that is there. */
        OPEN,

        /** Nothing exists yet: make an encrypted database. */
        CREATE,

        /** A readable database from an earlier version exists: copy it into an encrypted one. */
        CONVERT,
    }

    companion object {
        fun decide(plaintextExists: Boolean, encryptedExists: Boolean, key: KeyLookup): EncryptionPlan {
            if (encryptedExists && key is KeyLookup.Ready) return EncryptionPlan(Action.OPEN, discardPlaintext = plaintextExists)
            val next = if (plaintextExists) Action.CONVERT else Action.CREATE
            // An encrypted file whose key is gone is unreadable by anyone, this app included; a readable copy that is still there is the best that is left.
            return if (encryptedExists) EncryptionPlan(next, discardEncrypted = true, keyLost = true) else EncryptionPlan(next)
        }
    }
}
