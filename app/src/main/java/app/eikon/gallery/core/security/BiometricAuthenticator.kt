package app.eikon.gallery.core.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Whether the phone can authenticate the user at all. */
enum class AuthAvailability {
    /** Strong biometrics or the screen-lock PIN/pattern/password can be used. */
    AVAILABLE,

    /** No screen lock and no biometrics enrolled: there is nothing to check the user against. */
    NO_DEVICE_LOCK,

    /** Temporarily or permanently unusable (hardware busy, security update needed...). */
    UNAVAILABLE,
}

/**
 * Fingerprint / face / device PIN through the system BiometricPrompt. eikon never sees biometric data
 * or the PIN: the OS only reports success or failure.
 */
@Singleton
class BiometricAuthenticator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun availability(): AuthAvailability = when (BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)) {
        BiometricManager.BIOMETRIC_SUCCESS -> AuthAvailability.AVAILABLE
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
        -> AuthAvailability.NO_DEVICE_LOCK
        else -> AuthAvailability.UNAVAILABLE
    }

    /** Shows the system prompt. True only if the user authenticated; cancelling or failing is false. */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String?): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(AUTHENTICATORS)
                .build()
            prompt.authenticate(info)
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    private companion object {
        const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    }
}
