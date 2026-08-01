package io.github.jaypetez.ollamamobile.storage.crypto

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager.Authenticators
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two failure modes that lose user data if they are got wrong: the API 30
 * crypto/DEVICE_CREDENTIAL cliff, and recovery from a biometric enrolment
 * change destroying the Keystore key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class AppLockKeyGuardTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * A stand-in for the AndroidKeyStore, which does not exist on the host JVM.
     *
     * [invalidate] is the only way to simulate "the user enrolled a new
     * fingerprint" — nothing in a unit test can do that for real, and the
     * recovery path is precisely the code that must not be left untested.
     */
    private class FakeKeyMaterial : AppLockKeyMaterial {
        var key: SecretKey? = null
        var invalidated = false
        var generateCount = 0
        var lastSpec: AppLockPromptSpec? = null

        override fun load(): SecretKey? {
            if (invalidated) throw KeyPermanentlyInvalidatedException()
            return key
        }

        override fun generate(spec: AppLockPromptSpec): SecretKey {
            generateCount++
            lastSpec = spec
            invalidated = false
            return KeyGenerator
                .getInstance("AES")
                .apply { init(KEY_BITS) }
                .generateKey()
                .also { key = it }
        }

        override fun delete() {
            key = null
        }

        fun invalidate() {
            invalidated = true
            key = null
        }

        private companion object {
            const val KEY_BITS = 256
        }
    }

    private val keyMaterial = FakeKeyMaterial()

    private fun guard(
        policy: AppLockCredentialPolicy = AppLockCredentialPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL,
        sdkInt: Int = Build.VERSION_CODES.VANILLA_ICE_CREAM,
        file: File = File(temporaryFolder.root, AppLockKeyGuard.FILE_NAME),
    ) = AppLockKeyGuard(
        wrappedKeyFile = file,
        credentialPolicy = policy,
        sdkInt = sdkInt,
        keyMaterial = keyMaterial,
    )

    // -- The API 30 branch --------------------------------------------------

    @Test
    fun `below API 30 a crypto unlock never asks for DEVICE_CREDENTIAL`() {
        val spec = AppLockAuthenticators.promptSpec(
            AppLockCredentialPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL,
            sdkInt = Build.VERSION_CODES.Q,
        )

        // BiometricPrompt.authenticate(info, cryptoObject) throws
        // IllegalArgumentException outright if DEVICE_CREDENTIAL is set here.
        assertThat(spec.authenticators and Authenticators.DEVICE_CREDENTIAL).isEqualTo(0)
        assertThat(spec.authenticators).isEqualTo(Authenticators.BIOMETRIC_STRONG)
        assertThat(spec.deviceCredentialFallbackAvailable).isFalse()
        // Without DEVICE_CREDENTIAL a negative button becomes mandatory, and
        // omitting it is its own IllegalArgumentException.
        assertThat(spec.requiresNegativeButton).isTrue()
    }

    @Test
    fun `from API 30 a crypto unlock may offer DEVICE_CREDENTIAL and must not set a negative button`() {
        val spec = AppLockAuthenticators.promptSpec(
            AppLockCredentialPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL,
            sdkInt = Build.VERSION_CODES.R,
        )

        assertThat(spec.authenticators)
            .isEqualTo(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
        assertThat(spec.deviceCredentialFallbackAvailable).isTrue()
        assertThat(spec.requiresNegativeButton).isFalse()
    }

    @Test
    fun `an explicit biometric-only policy stays biometric-only above API 30`() {
        val spec = AppLockAuthenticators.promptSpec(
            AppLockCredentialPolicy.BIOMETRIC_ONLY,
            sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM,
        )

        assertThat(spec.authenticators).isEqualTo(Authenticators.BIOMETRIC_STRONG)
        assertThat(spec.requiresNegativeButton).isTrue()
    }

    @Test
    fun `the guard generates its key with the same authenticators the prompt will request`() {
        guard(sdkInt = Build.VERSION_CODES.Q).enable()

        // A key constrained to BIOMETRIC_STRONG cannot be unlocked by a prompt
        // that accepted a PIN; the mismatch only shows up as an opaque
        // KeyStoreException at Cipher.init on a real device.
        assertThat(keyMaterial.lastSpec?.deviceCredentialFallbackAvailable).isFalse()
    }

    // -- The lock is cryptographic ------------------------------------------

    @Test
    fun `enabling then unlocking round-trips the database key`() {
        val guard = guard()
        val original = (guard.enable() as DatabaseKeyResult.Unlocked).key

        val begun = guard.beginUnlock()
        assertThat(begun).isInstanceOf(DatabaseKeyResult.AuthenticationRequired::class.java)
        val cipher = (begun as DatabaseKeyResult.AuthenticationRequired).cipher

        val unlocked = guard.completeUnlock(cipher)
        assertThat((unlocked as DatabaseKeyResult.Unlocked).key.bytes).isEqualTo(original.bytes)
    }

    @Test
    fun `the persisted envelope never contains the database key in the clear`() {
        val file = File(temporaryFolder.root, AppLockKeyGuard.FILE_NAME)
        val key = (guard(file = file).enable() as DatabaseKeyResult.Unlocked).key

        val onDisk = Base64.getDecoder().decode(file.readText())
        assertThat(onDisk.toList().windowed(key.bytes.size)).doesNotContain(key.bytes.toList())
    }

    @Test
    fun `beginUnlock reports unavailable when the lock was never enabled`() {
        assertThat(guard().beginUnlock()).isInstanceOf(DatabaseKeyResult.Unavailable::class.java)
    }

    // -- Enrolment invalidation ---------------------------------------------

    @Test
    fun `a new biometric enrolment surfaces as Invalidated rather than throwing`() {
        val guard = guard()
        guard.enable()

        keyMaterial.invalidate()

        // The exception is raised by load() inside beginUnlock, i.e. before any
        // prompt is shown — which is what lets the UI explain itself.
        assertThat(guard.beginUnlock()).isInstanceOf(DatabaseKeyResult.Invalidated::class.java)
    }

    @Test
    fun `recovery after invalidation re-wraps a retained key and keeps the secrets`() {
        val guard = guard()
        val original = (guard.enable() as DatabaseKeyResult.Unlocked).key
        keyMaterial.invalidate()

        val recovery = guard.recoverFromInvalidation(retainedKey = original)

        assertThat(recovery.secretsMustBeReEntered).isFalse()
        assertThat((recovery.result as DatabaseKeyResult.Unlocked).key.bytes).isEqualTo(original.bytes)

        // And the re-minted lock actually works end to end.
        val begun = guard.beginUnlock() as DatabaseKeyResult.AuthenticationRequired
        assertThat((guard.completeUnlock(begun.cipher) as DatabaseKeyResult.Unlocked).key.bytes)
            .isEqualTo(original.bytes)
    }

    @Test
    fun `recovery with nothing retained mints a new key and demands re-entry`() {
        val guard = guard()
        val original = (guard.enable() as DatabaseKeyResult.Unlocked).key
        keyMaterial.invalidate()

        val recovery = guard.recoverFromInvalidation(retainedKey = null)

        assertThat(recovery.secretsMustBeReEntered).isTrue()
        val fresh = (recovery.result as DatabaseKeyResult.Unlocked).key
        assertThat(fresh.bytes).isNotEqualTo(original.bytes)
        assertThat(guard.isEnabled()).isTrue()
    }

    @Test
    fun `an envelope that no longer matches the key reports Invalidated, not a crash`() {
        val guard = guard()
        guard.enable()
        val begun = guard.beginUnlock() as DatabaseKeyResult.AuthenticationRequired

        // Re-enabling mints a new key and a new envelope; the cipher captured
        // above is now authorised for a key that decrypts nothing.
        guard.enable()

        assertThat(guard.completeUnlock(begun.cipher)).isInstanceOf(DatabaseKeyResult.Invalidated::class.java)
    }

    @Test
    fun `disabling the lock removes both the envelope and the key`() {
        val guard = guard()
        guard.enable()

        guard.disable()

        assertThat(guard.isEnabled()).isFalse()
        assertThat(keyMaterial.key).isNull()
    }
}
