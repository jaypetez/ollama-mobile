package io.github.jaypetez.ollamamobile.storage.crypto

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.SecretRef
import io.github.jaypetez.ollamamobile.model.ServerId
import java.io.File
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Keystore itself is not emulated on the host JVM, so [FakeSecretKeyProvider]
 * stands in for it. Everything above that seam — the per-value IV, the envelope
 * format, the DataStore round trip, and the invalidation path the UI turns into
 * a re-entry prompt — is the real code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretsStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val keyProvider = FakeSecretKeyProvider()
    private lateinit var store: SecretsStore

    @Before
    fun setUp() {
        store = SecretsStore(newDataStore("secrets"), keyProvider)
    }

    @Test
    fun `a stored secret round-trips`() = runTest {
        val ref = SecretRef.forServer(ServerId("s1"), "bearer")

        assertThat(store.put(ref, "hunter2")).isInstanceOf(SecretResult.Ok::class.java)

        assertThat(store.get(ref).getOrNull()).isEqualTo("hunter2")
    }

    @Test
    fun `an absent secret is not an error`() = runTest {
        val result = store.get(SecretRef.forServer(ServerId("nobody"), "bearer"))

        assertThat(result).isEqualTo(SecretResult.Ok(null))
    }

    @Test
    fun `non-ascii values survive the round trip`() = runTest {
        val ref = SecretRef("weird")
        val value = "pässwörd-日本語-🔐"

        store.put(ref, value)

        assertThat(store.get(ref).getOrNull()).isEqualTo(value)
    }

    @Test
    fun `each write uses a fresh IV`() = runTest {
        val a = SecretRef("a")
        val b = SecretRef("b")

        store.put(a, "same value")
        store.put(b, "same value")

        // Identical plaintext under the same key must not produce identical
        // ciphertext. If it did, the IV would be fixed, and a repeated GCM
        // nonce loses confidentiality and integrity at once.
        assertThat(rawValue(a)).isNotEqualTo(rawValue(b))
        assertThat(store.get(a).getOrNull()).isEqualTo(store.get(b).getOrNull())
    }

    @Test
    fun `the stored form is not the plaintext`() = runTest {
        val ref = SecretRef("token")
        store.put(ref, "sk-supersecret")

        assertThat(rawValue(ref)).doesNotContain("sk-supersecret")
    }

    @Test
    fun `overwriting replaces the value`() = runTest {
        val ref = SecretRef("token")
        store.put(ref, "old")
        store.put(ref, "new")

        assertThat(store.get(ref).getOrNull()).isEqualTo("new")
    }

    @Test
    fun `remove deletes the value`() = runTest {
        val ref = SecretRef("token")
        store.put(ref, "value")

        store.remove(ref)

        assertThat(store.contains(ref)).isFalse()
        assertThat(store.get(ref).getOrNull()).isNull()
    }

    @Test
    fun `forgetting a server deletes every secret it owned`() = runTest {
        val serverId = ServerId("s1")
        val bearer = SecretRef.forServer(serverId, "bearer")
        val basic = SecretRef.forServer(serverId, "basic")
        val other = SecretRef.forServer(ServerId("s2"), "bearer")
        store.put(bearer, "a")
        store.put(basic, "b")
        store.put(other, "c")

        store.forgetServer(serverId)

        assertThat(store.contains(bearer)).isFalse()
        assertThat(store.contains(basic)).isFalse()
        assertThat(store.contains(other)).isTrue()
    }

    @Test
    fun `observe emits the current value and then updates`() = runTest {
        val ref = SecretRef("token")
        store.put(ref, "first")

        assertThat(store.observe(ref).first().getOrNull()).isEqualTo("first")
    }

    // --- invalidation ----------------------------------------------------

    @Test
    fun `an invalidated key surfaces a typed error instead of crashing`() = runTest {
        val ref = SecretRef.forServer(ServerId("s1"), "bearer")
        store.put(ref, "hunter2")
        keyProvider.invalidated = true

        val result = store.get(ref)

        assertThat(result).isInstanceOf(SecretResult.Unavailable::class.java)
        val error = (result as SecretResult.Unavailable).error
        assertThat(error.ref).isEqualTo(ref)
        assertThat(error.message).contains("enter it again")
    }

    @Test
    fun `an invalidated key on write is reported the same way`() = runTest {
        keyProvider.invalidated = true

        val result = store.put(SecretRef("token"), "value")

        assertThat(result).isInstanceOf(SecretResult.Unavailable::class.java)
    }

    @Test
    fun `invalidation purges the undecryptable ciphertext`() = runTest {
        val ref = SecretRef("token")
        store.put(ref, "value")
        keyProvider.invalidated = true

        store.get(ref)
        // The purge dropped the key too, so the provider now hands out a fresh
        // one and the store is usable again after the user re-enters.
        keyProvider.invalidated = false

        assertThat(store.contains(ref)).isFalse()
        store.put(ref, "re-entered")
        assertThat(store.get(ref).getOrNull()).isEqualTo("re-entered")
    }

    @Test
    fun `ciphertext written under a different key is reported as unavailable`() = runTest {
        val ref = SecretRef("token")
        store.put(ref, "value")

        // What a restored backup looks like: the bytes are intact, the
        // device-bound key that made them is not.
        keyProvider.rotate()

        assertThat(store.get(ref)).isInstanceOf(SecretResult.Unavailable::class.java)
    }

    @Test
    fun `a corrupted envelope is reported as unavailable, not thrown`() = runTest {
        val store = SecretsStore(newDataStore("corrupt"), keyProvider)
        val ref = SecretRef("token")
        store.put(ref, "value")
        writeRaw("corrupt", ref, "not-base64-at-all!!!")

        assertThat(store.get(ref)).isInstanceOf(SecretResult.Unavailable::class.java)
    }

    // --- file placement ---------------------------------------------------

    @Test
    fun `the file is created under noBackupFilesDir`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val created = SecretsStore.create(context, keyProvider)

        created.put(SecretRef("token"), "value")

        val expected = File(context.noBackupFilesDir, SecretsStore.FILE_NAME)
        assertThat(expected.exists()).isTrue()
        // Nothing must land in the backed-up domain: the ciphertext is bound to
        // a device-specific Keystore key and is undecryptable after a restore.
        assertThat(File(context.filesDir, "datastore/${SecretsStore.FILE_NAME}").exists()).isFalse()
    }

    private val dataStores = mutableMapOf<String, DataStore<Preferences>>()

    private fun newDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory
            .create(
                produceFile = { File(temporaryFolder.root, "$name.preferences_pb") },
            ).also { dataStores[name] = it }

    private suspend fun rawValue(ref: SecretRef, name: String = "secrets"): String? =
        dataStores
            .getValue(name)
            .data
            .first()
            .asMap()
            .entries
            .firstOrNull { it.key.name == ref.alias }
            ?.value as String?

    private suspend fun writeRaw(name: String, ref: SecretRef, value: String) {
        dataStores.getValue(name).updateData { preferences ->
            preferences.toMutablePreferences().apply {
                set(
                    androidx.datastore.preferences.core
                        .stringPreferencesKey(ref.alias),
                    value,
                )
            }
        }
    }
}

/** An ordinary in-process AES-256 key, plus a switch for the invalidation path. */
private class FakeSecretKeyProvider : SecretKeyProvider {
    private var key: SecretKey = newKey()

    var invalidated: Boolean = false

    override fun secretKey(): SecretKey {
        if (invalidated) throw KeyPermanentlyInvalidatedException()
        return key
    }

    override fun destroy() {
        key = newKey()
        invalidated = false
    }

    /** Simulates a restore onto different hardware: same ciphertext, different key. */
    fun rotate() {
        key = newKey()
    }

    private companion object {
        fun newKey(): SecretKey = KeyGenerator
            .getInstance("AES")
            .apply { init(256, SecureRandom()) }
            .generateKey()
    }
}
