package app.eikon.gallery.data.backup

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The encryption of the API key and the app password. (On the phone the key is in the Android Keystore; here a plain AES key stands in.) */
class SecretBoxTest {
    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val box = SecretBox { key }

    @Test
    fun aSecretComesBackAsItWent() {
        assertEquals("s3cret-app-password", box.open("credential", box.seal("credential", "s3cret-app-password")))
    }

    @Test
    fun whatIsStoredDoesNotContainTheSecret() {
        val sealed = box.seal("credential", "s3cret-app-password")
        assertFalse("s3cret" in sealed)
    }

    @Test
    fun sealingTheSameSecretTwiceGivesDifferentText() {
        assertNotEquals(box.seal("credential", "same"), box.seal("credential", "same"))
    }

    @Test
    fun anotherKeyCannotOpenIt() {
        val sealed = box.seal("credential", "secret")
        assertNull(SecretBox { newKey() }.open("credential", sealed))
    }

    @Test
    fun aValueSealedForOneNameCannotBePassedOffAsAnother() {
        val sealed = box.seal("credential", "secret")
        assertNull(box.open("other", sealed))
    }

    @Test
    fun aValueThatWasAlteredIsRefused() {
        val sealed = box.seal("credential", "secret")
        val altered = sealed.dropLast(4) + (if (sealed.takeLast(4) == "AAAA") "BBBB" else "AAAA")
        assertNull(box.open("credential", altered))
    }

    @Test
    fun rubbishIsRefusedWithoutAnError() {
        assertNull(box.open("credential", "not base64 at all !!"))
        assertNull(box.open("credential", ""))
        assertNull(box.open("credential", "AAAA"))
    }

    @Test
    fun anEmptySecretAndUnicodeWork() {
        assertEquals("", box.open("x", box.seal("x", "")))
        assertEquals("pässwörd-日本語", box.open("x", box.seal("x", "pässwörd-日本語")))
    }

    @Test
    fun unsealingSaysRejectedOnlyWhenTheValueIsCertainlyNotOurs() {
        val sealed = box.seal("credential", "secret")

        assertEquals(Unsealed.Secret("secret"), box.unseal("credential", sealed))
        assertEquals(Unsealed.Rejected, SecretBox { newKey() }.unseal("credential", sealed))
        assertEquals(Unsealed.Rejected, box.unseal("other", sealed))
        assertEquals(Unsealed.Rejected, box.unseal("credential", "not base64 !!"))
        assertEquals(Unsealed.Rejected, box.unseal("credential", "AAAA"))
    }

    @Test
    fun aKeyThatCannotBeGotIsNotTakenForALostValue() {
        val sealed = box.seal("credential", "secret")
        val broken = SecretBox { throw IllegalStateException("the Keystore is busy") }

        try {
            broken.unseal("credential", sealed)
            org.junit.Assert.fail("a fault in getting the key says nothing about the value")
        } catch (e: IllegalStateException) {
            assertEquals("the Keystore is busy", e.message)
        }
    }

    @Test
    fun aKeyThatCannotBeUsedMakesOpenGiveNull() {
        val sealed = box.seal("credential", "secret")
        val unusable = SecretBox { throw java.security.InvalidKeyException("bad key") }

        assertNull(unusable.open("credential", sealed))
    }
}
