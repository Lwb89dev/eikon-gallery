package app.eikon.gallery.data.db.encryption

import app.eikon.gallery.data.backup.SecretRead
import app.eikon.gallery.data.backup.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The key of the database and how it is kept. */
class DatabaseKeysTest {
    private class Secrets : SecretStore {
        val values = HashMap<String, String>()
        var unreadable = false

        override fun get(name: String) = values[name]
        override fun put(name: String, value: String) {
            values[name] = value
        }

        override fun remove(name: String) {
            values.remove(name)
        }

        override fun read(name: String): SecretRead = when {
            name !in values -> SecretRead.Missing
            unreadable -> SecretRead.Unreadable
            else -> SecretRead.Value(values.getValue(name))
        }
    }

    private val secrets = Secrets()
    private val keys = DatabaseKeys(secrets)

    @Test
    fun aKeyIs256BitsAsABlobLiteralAndAsThePassphraseSqlcipherTakes() {
        val key = DatabaseKey.generate()
        val literal = key.sqlLiteral()

        assertTrue(literal, Regex("x'[0-9a-f]{64}'").matches(literal))
        assertEquals(literal, String(key.passphrase(), Charsets.US_ASCII))
    }

    @Test
    fun everyCallOfPassphraseGivesArrayOfItsOwn() {
        // The helper wipes the array it is given once it has used it.
        val key = DatabaseKey.generate()
        val first = key.passphrase()
        first.fill(0)
        assertEquals(key.sqlLiteral(), String(key.passphrase(), Charsets.US_ASCII))
    }

    @Test
    fun keysDoNotRepeat() {
        assertNotEquals(DatabaseKey.generate().stored(), DatabaseKey.generate().stored())
    }

    @Test
    fun onlyExactlyAKeyParsesAsAKey() {
        assertNull(DatabaseKey.parse(""))
        assertNull(DatabaseKey.parse("abc"))
        assertNull(DatabaseKey.parse("g".repeat(64)))
        assertNull(DatabaseKey.parse("A".repeat(64)))
        assertNull(DatabaseKey.parse("a".repeat(63)))
        assertNull(DatabaseKey.parse("a".repeat(65)))
        assertEquals("a".repeat(64), DatabaseKey.parse("a".repeat(64))!!.stored())
    }

    @Test
    fun nothingStoredMeansThereIsNoKeyYet() {
        assertEquals(KeyLookup.Absent, keys.lookup())
    }

    @Test
    fun aKeyThatWasMadeIsStoredAndComesBack() {
        val made = keys.create()

        val found = keys.lookup()

        assertTrue(found is KeyLookup.Ready)
        assertEquals(made.stored(), (found as KeyLookup.Ready).key.stored())
        assertEquals("what is stored is the key itself, for the store to seal", made.stored(), secrets.values.getValue("database-key"))
    }

    @Test
    fun aValueThatCannotBeOpenedIsALostKey() {
        keys.create()
        secrets.unreadable = true

        assertEquals(KeyLookup.Lost, keys.lookup())
    }

    @Test
    fun aValueThatIsNotAKeyIsALostKeyToo() {
        secrets.values["database-key"] = "not a key"

        assertEquals(KeyLookup.Lost, keys.lookup())
    }

    @Test
    fun aFailureOfTheStoreIsNotALostKey() {
        val failing = object : SecretStore {
            override fun get(name: String): String? = error("the Keystore is busy")
            override fun put(name: String, value: String) = Unit
            override fun remove(name: String) = Unit
            override fun read(name: String): SecretRead = error("the Keystore is busy")
        }

        try {
            DatabaseKeys(failing).lookup()
            org.junit.Assert.fail("a passing fault must not be read as the loss of the key")
        } catch (e: IllegalStateException) {
            assertFalse(e.message.isNullOrEmpty())
        }
    }
}
