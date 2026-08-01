package io.github.jaypetez.ollamamobile.storage.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The 32 raw bytes that unlock the database. Never persisted in the clear. */
@JvmInline
value class DatabaseKey(
    val bytes: ByteArray,
)

/**
 * The outcome of trying to obtain the database key.
 *
 * A sealed hierarchy rather than exceptions because every one of these is a
 * screen the user has to be shown, and three of them are *not* faults.
 */
sealed interface DatabaseKeyResult {
    data class Unlocked(
        val key: DatabaseKey,
    ) : DatabaseKeyResult

    /**
     * A wrapped key exists and the Keystore key is healthy; the user simply has
     * not authenticated yet. [cipher] is pre-initialised for decryption and is
     * the object that must be handed to `BiometricPrompt` inside a
     * `CryptoObject` — initialising a fresh one after the prompt would not be
     * authorised, because the authorisation binds to the operation, not to the
     * key.
     */
    data class AuthenticationRequired(
        val cipher: Cipher,
        val promptSpec: AppLockPromptSpec,
    ) : DatabaseKeyResult

    /**
     * The Keystore key was destroyed by a biometric enrolment change, and the
     * wrapped database key with it.
     *
     * The database itself is *not* lost — see [AppLockKeyGuard.recoverFromInvalidation].
     * What is lost is every server credential that was sealed under the app
     * lock, so the UI must drive a re-entry flow.
     */
    data class Invalidated(
        val cause: Throwable,
    ) : DatabaseKeyResult

    /** No hardware, or nothing enrolled. The caller decides whether to fall back. */
    data class Unavailable(
        val reason: String,
        val cause: Throwable? = null,
    ) : DatabaseKeyResult
}

/**
 * Generates and unwraps the database key under an AndroidKeyStore key that
 * requires user authentication.
 *
 * ## Why the key is wrapped rather than the lock being a screen
 *
 * A "lock screen" that a Compose navigation graph draws over the app is worth
 * nothing: the database file is already open, the process is already holding
 * the plaintext, and anyone with `adb` or a rooted device reads it without ever
 * meeting the UI. Here the database key exists only as ciphertext until
 * `BiometricPrompt` authorises one specific [Cipher] operation. Before that
 * happens there is no key material in the process to steal, and the database
 * genuinely cannot be opened — not "is not shown".
 *
 * ## The two ways this loses user data if it is written carelessly
 *
 * 1. **The API 30 cliff.** See [AppLockAuthenticators].
 * 2. **Enrolment invalidation.** [KeyGenParameterSpec.Builder.setInvalidatedByBiometricEnrollment]
 *    is set to `true`, deliberately: without it, anyone who can add their own
 *    fingerprint to an unlocked phone can then open the database, which defeats
 *    the entire control. The cost is that adding a fingerprint *permanently*
 *    destroys the Keystore key. Every read afterwards throws
 *    [KeyPermanentlyInvalidatedException], and a caller that lets that
 *    propagate crashes the app into an unrecoverable loop on next launch.
 *    So it is caught here, in every path that touches the key, and turned into
 *    [DatabaseKeyResult.Invalidated].
 */
class AppLockKeyGuard(
    private val wrappedKeyFile: File,
    private val credentialPolicy: AppLockCredentialPolicy = AppLockCredentialPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL,
    private val random: SecureRandom = SecureRandom(),
    private val sdkInt: Int = android.os.Build.VERSION.SDK_INT,
    private val keyMaterial: AppLockKeyMaterial = AndroidKeystoreAppLockKeyMaterial(sdkInt = sdkInt),
) {
    /** True once [enable] has stored a wrapped key. Cheap enough to call on the launch path. */
    fun isEnabled(): Boolean = wrappedKeyFile.exists()

    val promptSpec: AppLockPromptSpec get() = AppLockAuthenticators.promptSpec(credentialPolicy, sdkInt)

    /**
     * Turns the app lock on: mints a database key, wraps it under a freshly
     * generated user-authentication-required Keystore key and persists only the
     * envelope.
     *
     * Encryption is *not* gated on authentication even though the key requires
     * it — `PURPOSE_ENCRYPT` with a randomised IV is authorised without a
     * prompt on a per-operation key, which is what lets the lock be enabled in
     * one step instead of forcing a biometric prompt during setup.
     */
    fun enable(existingKey: DatabaseKey? = null): DatabaseKeyResult = try {
        keyMaterial.delete()
        val databaseKey = existingKey ?: DatabaseKey(ByteArray(DATABASE_KEY_BYTES).also(random::nextBytes))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyMaterial.generate(promptSpec))
        val ciphertext = cipher.doFinal(databaseKey.bytes)
        writeEnvelope(cipher.iv, ciphertext)
        DatabaseKeyResult.Unlocked(databaseKey)
    } catch (e: KeyPermanentlyInvalidatedException) {
        DatabaseKeyResult.Invalidated(e)
    } catch (e: GeneralSecurityException) {
        DatabaseKeyResult.Unavailable("The device could not create a hardware-backed app-lock key.", e)
    }

    /**
     * Step one of unlocking: hands back a [Cipher] that is initialised but not
     * yet authorised.
     *
     * `Cipher.init` is where [KeyPermanentlyInvalidatedException] is thrown, not
     * `doFinal` — so the enrolment-change case is detected *before* a prompt is
     * shown to the user, which is the difference between "please enter your
     * token again" and a biometric dialog that fails for no visible reason.
     */
    fun beginUnlock(): DatabaseKeyResult {
        val envelope = readEnvelope() ?: return DatabaseKeyResult.Unavailable("The app lock is not enabled.")
        return try {
            val key = keyMaterial.load() ?: return DatabaseKeyResult.Invalidated(
                IllegalStateException("The app-lock key is missing from the Keystore."),
            )
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, envelope.iv))
            DatabaseKeyResult.AuthenticationRequired(cipher, promptSpec)
        } catch (e: KeyPermanentlyInvalidatedException) {
            DatabaseKeyResult.Invalidated(e)
        } catch (e: GeneralSecurityException) {
            DatabaseKeyResult.Unavailable("The app-lock key could not be prepared.", e)
        }
    }

    /**
     * Step two: unwraps the database key with the [Cipher] that
     * `BiometricPrompt` just authorised.
     *
     * @param authorisedCipher must be the very object returned inside
     *   [DatabaseKeyResult.AuthenticationRequired] and handed to the prompt.
     */
    fun completeUnlock(authorisedCipher: Cipher): DatabaseKeyResult {
        val envelope = readEnvelope() ?: return DatabaseKeyResult.Unavailable("The app lock is not enabled.")
        return try {
            DatabaseKeyResult.Unlocked(DatabaseKey(authorisedCipher.doFinal(envelope.ciphertext)))
        } catch (e: KeyPermanentlyInvalidatedException) {
            DatabaseKeyResult.Invalidated(e)
        } catch (e: GeneralSecurityException) {
            // A failed GCM tag here means the envelope no longer matches the
            // key. Same user-visible situation as invalidation, same fix.
            DatabaseKeyResult.Invalidated(e)
        }
    }

    /**
     * Recovers from [DatabaseKeyResult.Invalidated] by re-minting the app lock
     * around a database key the caller can still supply.
     *
     * The distinction that keeps a user's conversations: the *database* key and
     * the *app-lock* key are different keys. Losing the app-lock key makes the
     * stored envelope undecryptable, but if the caller holds the database key
     * from an already-unlocked session — or the database was never encrypted at
     * rest to begin with — the whole recovery is "re-wrap it under a new
     * Keystore key". Only when nothing can supply the old key is data actually
     * gone, and then this mints a new one and the caller must re-enter secrets.
     *
     * @return the fresh [DatabaseKeyResult.Unlocked], and true in
     *   [InvalidationRecovery.secretsMustBeReEntered] when the old key could not
     *   be carried across.
     */
    fun recoverFromInvalidation(retainedKey: DatabaseKey? = null): InvalidationRecovery {
        keyMaterial.delete()
        wrappedKeyFile.delete()
        return InvalidationRecovery(
            result = enable(existingKey = retainedKey),
            secretsMustBeReEntered = retainedKey == null,
        )
    }

    /** Turns the lock off and destroys the Keystore key. */
    fun disable() {
        keyMaterial.delete()
        wrappedKeyFile.delete()
    }

    // -- Envelope -----------------------------------------------------------

    private class Envelope(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    private fun writeEnvelope(iv: ByteArray, ciphertext: ByteArray) {
        val bytes = ByteArray(HEADER_BYTES + iv.size + ciphertext.size)
        bytes[0] = ENVELOPE_VERSION
        bytes[1] = iv.size.toByte()
        iv.copyInto(bytes, destinationOffset = HEADER_BYTES)
        ciphertext.copyInto(bytes, destinationOffset = HEADER_BYTES + iv.size)
        wrappedKeyFile.parentFile?.mkdirs()
        wrappedKeyFile.writeText(Base64.getEncoder().encodeToString(bytes))
    }

    private fun readEnvelope(): Envelope? {
        if (!wrappedKeyFile.exists()) return null
        val bytes = runCatching { Base64.getDecoder().decode(wrappedKeyFile.readText()) }.getOrNull() ?: return null
        if (bytes.size <= HEADER_BYTES || bytes[0] != ENVELOPE_VERSION) return null
        val ivLength = bytes[1].toInt()
        if (ivLength <= 0 || bytes.size <= HEADER_BYTES + ivLength) return null
        return Envelope(
            iv = bytes.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivLength),
            ciphertext = bytes.copyOfRange(HEADER_BYTES + ivLength, bytes.size),
        )
    }

    companion object {
        const val FILE_NAME: String = "applock-wrapped-key"

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DATABASE_KEY_BYTES = 32
        private const val GCM_TAG_BITS = 128
        private const val ENVELOPE_VERSION: Byte = 1
        private const val HEADER_BYTES = 2

        /** Under `noBackupFilesDir` for the same reason as the secrets file: a restored envelope can never decrypt. */
        fun create(
            context: Context,
            credentialPolicy: AppLockCredentialPolicy = AppLockCredentialPolicy.BIOMETRIC_OR_DEVICE_CREDENTIAL,
        ): AppLockKeyGuard = AppLockKeyGuard(
            wrappedKeyFile = File(context.applicationContext.noBackupFilesDir, FILE_NAME),
            credentialPolicy = credentialPolicy,
        )
    }
}

/** The result of [AppLockKeyGuard.recoverFromInvalidation]. */
data class InvalidationRecovery(
    val result: DatabaseKeyResult,
    val secretsMustBeReEntered: Boolean,
)

/**
 * Supplies the user-authentication-required Keystore key.
 *
 * An interface for the same reason [SecretKeyProvider] is one: the
 * AndroidKeyStore provider does not exist on the host JVM that Robolectric runs
 * on, and the behaviour worth testing — the API-30 branch, the envelope, and
 * above all the recovery from [KeyPermanentlyInvalidatedException] — all sits
 * above this seam. A fake that throws on demand is the only way to exercise an
 * enrolment change at all, since no unit test can enrol a fingerprint.
 */
interface AppLockKeyMaterial {
    /** @throws KeyPermanentlyInvalidatedException if the key exists but a new enrolment destroyed it. */
    fun load(): SecretKey?

    fun generate(spec: AppLockPromptSpec): SecretKey

    fun delete()
}

/** The real thing: AES-256-GCM in the AndroidKeyStore, unusable without a fresh authentication. */
class AndroidKeystoreAppLockKeyMaterial(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
    private val sdkInt: Int = android.os.Build.VERSION.SDK_INT,
) : AppLockKeyMaterial {
    override fun load(): SecretKey? = (keyStore().getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey

    override fun delete() {
        runCatching { keyStore().deleteEntry(keyAlias) }
    }

    override fun generate(spec: AppLockPromptSpec): SecretKey = try {
        generateWith(spec, strongBox = true)
    } catch (_: StrongBoxUnavailableException) {
        // StrongBox is a discrete secure element and is far from universal;
        // asking for it where it does not exist throws rather than degrading.
        generateWith(spec, strongBox = false)
    }

    private fun generateWith(spec: AppLockPromptSpec, strongBox: Boolean): SecretKey {
        val builder = KeyGenParameterSpec
            .Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            // See AppLockKeyGuard's class comment: this is what makes "enrol a
            // new fingerprint, then open the database" impossible, and it is
            // also what makes the recovery path mandatory.
            .setInvalidatedByBiometricEnrollment(true)
            .setIsStrongBoxBacked(strongBox)
        AppLockAuthenticators.applyUserAuthentication(builder, spec, sdkInt)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val DEFAULT_KEY_ALIAS: String = "io.github.jaypetez.ollamamobile.applock.v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}
