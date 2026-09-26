package app.eikon.gallery.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.eikon.gallery.data.backup.KeystoreSecretStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The credential's storage on the phone, with the real Android Keystore (the JVM tests use a plain AES key in its place): what is stored is not the secret, it comes back,
 * and one name's value cannot be read as another's. It uses names of its own and removes them, and never touches the backup's real credential.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreSecretStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = KeystoreSecretStore(context, "instrumented_test_secrets", "eikon-instrumented-test-key")
    private val name = "instrumented-test-secret"
    private val other = "instrumented-test-other"

    @After
    fun cleanUp() {
        store.remove(name)
        store.remove(other)
    }

    @Test
    fun aSecretComesBackAsItWent() {
        store.put(name, "an-api-key-with-ünïcode")
        assertEquals("an-api-key-with-ünïcode", store.get(name))
    }

    @Test
    fun whatIsWrittenToDiskIsNotTheSecret() {
        store.put(name, "plain-text-should-not-appear")
        val file = java.io.File(context.dataDir, "shared_prefs/instrumented_test_secrets.xml")
        assertFalse(file.readText().contains("plain-text-should-not-appear"))
    }

    @Test
    fun aRemovedSecretIsGone() {
        store.put(name, "x")
        store.remove(name)
        assertNull(store.get(name))
    }

    @Test
    fun oneNamesValueIsNotAnother() {
        store.put(name, "first")
        store.put(other, "second")
        assertEquals("first", store.get(name))
        assertEquals("second", store.get(other))
    }
}
