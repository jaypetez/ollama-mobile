package io.github.jaypetez.ollamamobile.storage.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager.Authenticators

/**
 * What the user is willing to unlock the database with.
 *
 * Separate from the *achievable* answer in [AppLockPromptSpec]: the user asks
 * for device-credential fallback, the platform decides whether that is possible
 * alongside a [javax.crypto.Cipher], and those two facts must not be conflated
 * or the API-29 branch below has nowhere to live.
 */
enum class AppLockCredentialPolicy {
    /** A class-3 biometric only. Always available where any biometric is. */
    BIOMETRIC_ONLY,

    /** A class-3 biometric, or the PIN/pattern/password as a fallback. */
    BIOMETRIC_OR_DEVICE_CREDENTIAL,
}

/**
 * The concrete arguments for one `BiometricPrompt.authenticate` call.
 *
 * @param authenticators the [Authenticators] bitmask to put on the prompt info
 *   *and* the matching constraint on the Keystore key. They must agree: a key
 *   that permits only `AUTH_BIOMETRIC_STRONG` cannot be unlocked by a prompt
 *   that accepted a PIN, and the failure surfaces as an opaque
 *   `KeyStoreException` at `Cipher.init` rather than anywhere useful.
 * @param requiresNegativeButton whether `setNegativeButtonText` must be called.
 *   `BiometricPrompt` throws if a negative button is supplied *with*
 *   `DEVICE_CREDENTIAL`, and throws if it is *absent* without it — so this is
 *   not cosmetic, it is a third thing that has to track the same branch.
 * @param deviceCredentialFallbackAvailable false when the user asked for
 *   PIN fallback and the platform refused it, so the UI can say why the prompt
 *   is biometric-only instead of silently dropping the request.
 */
data class AppLockPromptSpec(
    val authenticators: Int,
    val requiresNegativeButton: Boolean,
    val deviceCredentialFallbackAvailable: Boolean,
)

/**
 * Chooses the authenticator set for a *crypto-backed* unlock.
 *
 * ## The API 30 branch, which is the whole reason this object exists
 *
 * `BiometricPrompt.authenticate(promptInfo, cryptoObject)` rejects an
 * authenticator set containing `DEVICE_CREDENTIAL` on anything below API 30. It
 * does not degrade, it does not ignore the flag: it throws
 * `IllegalArgumentException("Crypto-based authentication is not supported for
 * Device Credential prior to API 30")` synchronously, before the prompt is ever
 * shown. `minSdk` here is 29, so that is a live crash on a supported device and
 * not a theoretical one.
 *
 * The Keystore side has the identical cliff from the other direction:
 * [KeyGenParameterSpec.Builder.setUserAuthenticationParameters] — the only way
 * to say "this key accepts a biometric *or* the device credential" — was added
 * in API 30 too. On 29 the only expressible policy is the deprecated
 * `setUserAuthenticationValidityDurationSeconds(-1)`, whose `-1` means
 * "authentication required for every single use", which is exactly the
 * per-operation, biometric-only, `CryptoObject` behaviour we want and the only
 * one available.
 *
 * So on API 29 a crypto-backed lock is biometric-only. That is a real reduction
 * in what the user asked for, which is why it is reported in
 * [AppLockPromptSpec.deviceCredentialFallbackAvailable] rather than quietly
 * applied.
 */
object AppLockAuthenticators {
    /**
     * @param sdkInt taken as a parameter rather than read from
     *   [Build.VERSION.SDK_INT] so a unit test can assert both sides of the
     *   cliff without Robolectric's `@Config(sdk = ...)` per case.
     */
    fun promptSpec(policy: AppLockCredentialPolicy, sdkInt: Int = Build.VERSION.SDK_INT): AppLockPromptSpec {
        val credentialWanted = policy == AppLockCredentialPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL
        val credentialUsable = credentialWanted && sdkInt >= Build.VERSION_CODES.R
        return if (credentialUsable) {
            AppLockPromptSpec(
                authenticators = Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
                // Supplying one alongside DEVICE_CREDENTIAL is itself an
                // IllegalArgumentException; the system draws its own.
                requiresNegativeButton = false,
                deviceCredentialFallbackAvailable = true,
            )
        } else {
            AppLockPromptSpec(
                authenticators = Authenticators.BIOMETRIC_STRONG,
                requiresNegativeButton = true,
                deviceCredentialFallbackAvailable = false,
            )
        }
    }

    /**
     * Applies the matching user-authentication constraint to a key being
     * generated. Mirrors [promptSpec] and must be changed with it.
     */
    fun applyUserAuthentication(
        builder: KeyGenParameterSpec.Builder,
        spec: AppLockPromptSpec,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): KeyGenParameterSpec.Builder {
        builder.setUserAuthenticationRequired(true)
        if (supportsAuthenticationParameters(sdkInt)) {
            applyModernUserAuthentication(builder, spec)
        } else {
            // -1 is not "no timeout", it is the documented sentinel for
            // "authenticate for every use", i.e. per-operation auth bound to
            // the CryptoObject. Deprecated in 30, and the only option in 29.
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        return builder
    }

    /**
     * Lint cannot see through a version taken as a parameter, and the parameter
     * is what makes both sides of the cliff testable. This annotation is the
     * supported way to tell it that a true return implies API 30 — the
     * alternative, suppressing NewApi at the call site, would silence the check
     * for everything else in that function too.
     */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
    private fun supportsAuthenticationParameters(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.R

    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyModernUserAuthentication(builder: KeyGenParameterSpec.Builder, spec: AppLockPromptSpec) {
        val types = if (spec.deviceCredentialFallbackAvailable) {
            KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
        } else {
            KeyProperties.AUTH_BIOMETRIC_STRONG
        }
        // Timeout 0 means per-operation authentication: the unlock authorises
        // this one Cipher and nothing else. Any positive value would authorise
        // every use of the key for that many seconds, which turns "the database
        // cannot open without a fingerprint" into "the database cannot open
        // more than once every N seconds without a fingerprint".
        builder.setUserAuthenticationParameters(0, types)
    }
}
