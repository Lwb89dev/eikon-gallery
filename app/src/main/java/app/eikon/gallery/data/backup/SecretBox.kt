package app.eikon.gallery.data.backup

import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the API key, the app password and the database key are kept between runs. */
interface SecretStore {
    fun get(name: String): String?
    fun put(name: String, value: String)
    fun remove(name: String)

    /**
     * Like [get], but says why there is nothing: never stored, or stored and impossible to open (the Keystore key is gone, or the value was altered). A failure of the Keystore itself is
     * neither: it is an exception, so that nobody takes a passing fault for a lost key. Stores that cannot tell the two apart count a missing value as never stored.
     */
    fun read(name: String): SecretRead = get(name)?.let { SecretRead.Value(it) } ?: SecretRead.Missing

    /** Whether something is stored under [name], without opening it (opening asks the Keystore, which a screen should not wait for). */
    fun has(name: String): Boolean = get(name) != null

    /**
     * Seals [value] for keeping somewhere that is not this store (a settings file), and [unseal] reverses it: null if it cannot be opened. Stores that do not encrypt (the ones the tests
     * use) return the value as it is.
     */
    fun seal(name: String, value: String): String = value

    fun unseal(name: String, sealed: String): String? = sealed
}

sealed interface SecretRead {
    data object Missing : SecretRead
    data object Unreadable : SecretRead
    data class Value(val value: String) : SecretRead
}

/** What opening a sealed value gave: the secret, or the certainty that this box did not make it for that name. */
sealed interface Unsealed {
    data class Secret(val value: String) : Unsealed
    data object Rejected : Unsealed
}

/**
 * Encrypts short secrets with AES-256-GCM under a key that never leaves [key]'s owner (on the phone, the Android Keystore: the key cannot be read out, so what is
 * stored is useless without this app on this device). Each secret gets its own nonce, and its [name] is authenticated together with it, so a stored value cannot
 * be swapped for another name's.
 */
class SecretBox(private val key: () -> SecretKey) {
    fun seal(name: String, secret: String): String {
        // The nonce is made by the cipher itself (the Keystore refuses a nonce chosen by the caller) and stored in front of what it produces.
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(name.toByteArray())
        }
        val sealed = cipher.doFinal(secret.toByteArray())
        check(cipher.iv.size == NONCE_BYTES) { "unexpected nonce length ${cipher.iv.size}" }
        return Base64.getEncoder().encodeToString(cipher.iv + sealed)
    }

    /** The secret, or null if [sealed] is not something this box made for [name] (wrong key, altered, or another name's) or the key could not be used. */
    fun open(name: String, sealed: String): String? = try {
        (unseal(name, sealed) as? Unsealed.Secret)?.value
    } catch (_: java.security.GeneralSecurityException) {
        null
    }

    /**
     * The secret, or [Unsealed.Rejected] when the text is malformed or fails authentication (wrong key, altered, another name's): the only ways a stored value is *certainly* not readable.
     * Any other failure, such as the Keystore not answering, is thrown, because it says nothing about the value and must not be taken for its loss.
     */
    fun unseal(name: String, sealed: String): Unsealed {
        val secretKey = key()
        return try {
            val bytes = Base64.getDecoder().decode(sealed)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
                updateAAD(name.toByteArray())
            }
            Unsealed.Secret(String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES)))
        } catch (_: AEADBadTagException) {
            Unsealed.Rejected
        } catch (_: IllegalArgumentException) {
            Unsealed.Rejected
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
