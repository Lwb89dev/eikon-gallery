package app.eikon.gallery.data.db.encryption

import app.eikon.gallery.data.db.encryption.EncryptionPlan.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** What is done about the database at start-up, for every combination of what is on disk and what is known about the key. */
class EncryptionPlanTest {
    private val ready = KeyLookup.Ready(DatabaseKey.generate())

    @Test
    fun aFirstLaunchMakesAnEncryptedDatabase() {
        assertEquals(EncryptionPlan(Action.CREATE), EncryptionPlan.decide(plaintextExists = false, encryptedExists = false, key = KeyLookup.Absent))
    }

    @Test
    fun aReadableDatabaseIsConverted() {
        assertEquals(EncryptionPlan(Action.CONVERT), EncryptionPlan.decide(plaintextExists = true, encryptedExists = false, key = KeyLookup.Absent))
    }

    @Test
    fun aConversionThatWasInterruptedIsDoneAgainWithTheSameKey() {
        assertEquals(EncryptionPlan(Action.CONVERT), EncryptionPlan.decide(plaintextExists = true, encryptedExists = false, key = ready))
    }

    @Test
    fun aStoredKeyThatCannotBeReadDoesNotStopAFirstLaunchOrAConversion() {
        // No encrypted file exists, so nothing is lost by making a new key.
        assertEquals(EncryptionPlan(Action.CREATE), EncryptionPlan.decide(plaintextExists = false, encryptedExists = false, key = KeyLookup.Lost))
        assertEquals(EncryptionPlan(Action.CONVERT), EncryptionPlan.decide(plaintextExists = true, encryptedExists = false, key = KeyLookup.Lost))
    }

    @Test
    fun anEncryptedDatabaseWithItsKeyIsOpened() {
        assertEquals(EncryptionPlan(Action.OPEN), EncryptionPlan.decide(plaintextExists = false, encryptedExists = true, key = ready))
    }

    @Test
    fun aReadableCopyLeftBesideTheEncryptedOneIsDiscarded() {
        assertEquals(EncryptionPlan(Action.OPEN, discardPlaintext = true), EncryptionPlan.decide(plaintextExists = true, encryptedExists = true, key = ready))
    }

    @Test
    fun anEncryptedDatabaseWithoutItsKeyIsDiscardedAndTheUserToldWhateverElseThereIs() {
        val lost = EncryptionPlan(Action.CREATE, discardEncrypted = true, keyLost = true)
        assertEquals(lost, EncryptionPlan.decide(plaintextExists = false, encryptedExists = true, key = KeyLookup.Lost))
        assertEquals(lost, EncryptionPlan.decide(plaintextExists = false, encryptedExists = true, key = KeyLookup.Absent))
    }

    @Test
    fun aReadableCopyIsUsedWhenTheEncryptedOneCannotBeOpened() {
        val plan = EncryptionPlan.decide(plaintextExists = true, encryptedExists = true, key = KeyLookup.Lost)
        assertEquals(EncryptionPlan(Action.CONVERT, discardEncrypted = true, keyLost = true), plan)
    }
}
