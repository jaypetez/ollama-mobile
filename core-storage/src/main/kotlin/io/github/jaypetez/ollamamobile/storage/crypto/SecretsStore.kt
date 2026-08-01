package io.github.jaypetez.ollamamobile.storage.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jaypetez.ollamamobile.model.AppError
import io.github.jaypetez.ollamamobile.model.SecretRef
import io.github.jaypetez.ollamamobile.model.ServerId
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The outcome of a secrets operation.
 *
 * Not `Result<T>`: [Unavailable] is a *product state* the UI has to render as
 * "this credential is gone, please enter it again", and burying it in a
 * `Throwable` invites a call site to `getOrNull()` it into a silent null that
 * then looks like "no token configured".
 */
sealed interface SecretResult<out T> {
    data class Ok<out T>(
        val value: T,
    ) : SecretResult<T>

    /**
     * The Keystore key that protected this value no longer exists or no longer
     * decrypts. Nothing is recoverable; the UI must prompt for re-entry.
     */
    data class Unavailable(
        val error: AppError.Storage.SecretUnavailable,
    ) : SecretResult<Nothing>

    /** Everything else — an I/O failure on the DataStore file, typically. */
    data class Failed(
        val error: AppError,
    ) : SecretResult<Nothing>

    fun getOrNull(): T? = (this as? Ok)?.value

    val errorOrNull: AppError?
        get() = when (this) {
            is Ok -> null
            is Unavailable -> error
            is Failed -> error
        }
}

/**
 * Supplies the AES key that protects stored secrets.
 *
 * An interface only so tests can substitute a plain in-memory key: the
 * AndroidKeyStore provider is not emulated on the host JVM, and the parts worth
 * testing — the IV handling, the envelope encoding, the DataStore round trip
 * and the invalidation path — are all above this seam.
 */
interface SecretKeyProvider {
    /** @throws KeyPermanentlyInvalidatedException if the key exists but can no longer be used. */
    fun secretKey(): SecretKey

    /** Drops the key. The next [secretKey] call generates a fresh one. */
    fun destroy()
}

/**
 * Encrypted key/value storage for bearer tokens, basic-auth passwords and
 * custom header values.
 *
 * ## Why not `EncryptedSharedPreferences`
 *
 * `androidx.security:security-crypto` is **deprecated** and is not used here.
 * Its replacement is exactly this shape: ciphertext in an ordinary DataStore
 * file, with the key generated in and never leaving the Android Keystore.
 *
 * ## Layout
 *
 * The file lives under [Context.getNoBackupFilesDir]. That is belt and braces
 * next to the explicit `secrets.preferences_pb` exclusions in
 * `backup_rules.xml` and `data_extraction_rules.xml`: the Keystore key is
 * device-bound and non-exportable, so ciphertext restored onto a new device is
 * undecryptable garbage. Better that the user re-enters a token than that the
 * app restores something which fails mysteriously months later.
 *
 * Each value is sealed as `[version][ivLength][iv][ciphertext+tag]`, base64'd.
 * The IV is freshly generated per write — GCM catastrophically loses
 * confidentiality *and* integrity if a nonce is ever reused under the same key,
 * so it is stored alongside the ciphertext rather than derived from anything.
 */
class SecretsStore(
    private val dataStore: DataStore<Preferences>,
    private val keyProvider: SecretKeyProvider,
) {
    suspend fun put(ref: SecretRef, value: String): SecretResult<Unit> = try {
        val sealed = seal(value)
        dataStore.edit { it[stringPreferencesKey(ref.alias)] = sealed }
        SecretResult.Ok(Unit)
    } catch (e: KeyPermanentlyInvalidatedException) {
        purgeAfterInvalidation()
        SecretResult.Unavailable(invalidated(ref, e))
    } catch (e: GeneralSecurityException) {
        SecretResult.Failed(AppError.Storage.Io("Could not encrypt ${ref.alias}.", e))
    } catch (e: IOException) {
        SecretResult.Failed(AppError.Storage.Io("Could not write the secrets file.", e))
    }

    /** [SecretResult.Ok] with a null value means "no such secret", which is not an error. */
    suspend fun get(ref: SecretRef): SecretResult<String?> = try {
        val sealed = dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .map { it[stringPreferencesKey(ref.alias)] }
            .first()
        if (sealed == null) SecretResult.Ok(null) else SecretResult.Ok(unseal(sealed))
    } catch (e: KeyPermanentlyInvalidatedException) {
        purgeAfterInvalidation()
        SecretResult.Unavailable(invalidated(ref, e))
    } catch (e: GeneralSecurityException) {
        // A bad GCM tag means the ciphertext no longer matches the key: a
        // restored backup, a re-provisioned device, a partially written file.
        // Indistinguishable from invalidation from here, and the fix is the
        // same, so report it the same way instead of guessing.
        SecretResult.Unavailable(invalidated(ref, e))
    } catch (e: IOException) {
        SecretResult.Failed(AppError.Storage.Io("Could not read the secrets file.", e))
    }

    /** Re-emits whenever the stored value changes, so a settings screen can react to a revocation. */
    fun observe(ref: SecretRef): Flow<SecretResult<String?>> = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { preferences ->
            val sealed = preferences[stringPreferencesKey(ref.alias)]
            when {
                sealed == null -> SecretResult.Ok(null)

                else -> try {
                    SecretResult.Ok(unseal(sealed))
                } catch (e: KeyPermanentlyInvalidatedException) {
                    SecretResult.Unavailable(invalidated(ref, e))
                } catch (e: GeneralSecurityException) {
                    SecretResult.Unavailable(invalidated(ref, e))
                }
            }
        }

    suspend fun contains(ref: SecretRef): Boolean = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { it.contains(stringPreferencesKey(ref.alias)) }
        .first()

    suspend fun remove(ref: SecretRef) {
        dataStore.edit { it.remove(stringPreferencesKey(ref.alias)) }
    }

    /**
     * Deletes every secret belonging to a server.
     *
     * Forgetting a server that leaves its bearer token behind is a bug, and the
     * alias namespace (`server.<id>.<purpose>`) is what makes this a prefix
     * scan rather than a list of purposes to keep in sync.
     */
    suspend fun forgetServer(serverId: ServerId) {
        val prefix = "server.${serverId.value}."
        dataStore.edit { preferences ->
            preferences
                .asMap()
                .keys
                .filter { it.name.startsWith(prefix) }
                .forEach { preferences.remove(stringPreferencesKey(it.name)) }
        }
    }

    /** Drops the Keystore key and every value it protected. */
    suspend fun clear() {
        keyProvider.destroy()
        dataStore.edit { it.clear() }
    }

    private fun seal(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider.secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val envelope = ByteArray(2 + iv.size + ciphertext.size)
        envelope[0] = ENVELOPE_VERSION
        envelope[1] = iv.size.toByte()
        iv.copyInto(envelope, destinationOffset = 2)
        ciphertext.copyInto(envelope, destinationOffset = 2 + iv.size)
        return Base64.getEncoder().encodeToString(envelope)
    }

    private fun unseal(sealed: String): String {
        val envelope = try {
            Base64.getDecoder().decode(sealed)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("Stored secret is not valid base64.", e)
        }
        if (envelope.size < 3 || envelope[0] != ENVELOPE_VERSION) {
            throw GeneralSecurityException("Unrecognised secret envelope.")
        }
        val ivLength = envelope[1].toInt()
        if (ivLength <= 0 || envelope.size <= 2 + ivLength) {
            throw GeneralSecurityException("Truncated secret envelope.")
        }
        val iv = envelope.copyOfRange(2, 2 + ivLength)
        val ciphertext = envelope.copyOfRange(2 + ivLength, envelope.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider.secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Once the key is gone every stored value is permanently undecryptable, so
     * keeping the ciphertext only guarantees that every future read fails the
     * same way. Purging lets the next `put` start from a fresh key.
     */
    private suspend fun purgeAfterInvalidation() {
        runCatching {
            keyProvider.destroy()
            dataStore.edit { it.clear() }
        }
    }

    private fun invalidated(ref: SecretRef, cause: Throwable): AppError.Storage.SecretUnavailable =
        AppError.Storage.SecretUnavailable(
            ref = ref,
            message = "The stored credential for ${ref.alias} can no longer be decrypted. Please enter it again.",
            cause = cause,
        )

    companion object {
        const val FILE_NAME: String = "secrets.preferences_pb"

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val ENVELOPE_VERSION: Byte = 1

        /**
         * [Context.getNoBackupFilesDir], not `filesDir`: DataStore's default
         * location is inside the backed-up domain, and the exclusion rules in
         * the manifest are then the only thing standing between a Keystore-bound
         * ciphertext and a cloud backup that can never decrypt it.
         */
        fun create(context: Context, keyProvider: SecretKeyProvider): SecretsStore {
            val appContext = context.applicationContext
            val dataStore = PreferenceDataStoreFactory.create(
                produceFile = { File(appContext.noBackupFilesDir, FILE_NAME) },
            )
            return SecretsStore(dataStore, keyProvider)
        }
    }
}

/**
 * An AES-256-GCM key generated in, and never leaving, the Android Keystore.
 *
 * `setUserAuthenticationRequired(false)` on purpose: gating *use* of a token
 * behind biometrics breaks background sync and model-list refresh. Gating the
 * *reveal* of a token in the UI is a separate, worthwhile control and belongs
 * in the UI layer.
 */
class AndroidKeystoreSecretKeyProvider(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : SecretKeyProvider {
    override fun secretKey(): SecretKey {
        val existing = keyStore().getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        return existing?.secretKey ?: generate()
    }

    override fun destroy() {
        runCatching { keyStore().deleteEntry(keyAlias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun generate(): SecretKey = try {
        // StrongBox is a separate secure element and is not universal; asking
        // for it on hardware that lacks it throws rather than degrading, so the
        // fallback is mandatory rather than defensive.
        generateWith(strongBox = true)
    } catch (_: StrongBoxUnavailableException) {
        generateWith(strongBox = false)
    }

    private fun generateWith(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec
            .Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_KEY_ALIAS: String = "io.github.jaypetez.ollamamobile.secrets.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}
