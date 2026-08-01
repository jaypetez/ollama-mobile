package io.github.jaypetez.ollamamobile.data

import io.github.jaypetez.ollamamobile.model.SecretRef
import io.github.jaypetez.ollamamobile.remote.SecretResolver
import io.github.jaypetez.ollamamobile.storage.crypto.SecretResult
import io.github.jaypetez.ollamamobile.storage.crypto.SecretsStore
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * The join `:core-remote` deliberately left open.
 *
 * `:core-remote` declares [SecretResolver] because it needs one, and
 * `:core-storage` owns the Keystore that can answer it — but neither may
 * depend on the other. `:core-data` is the first module that sees both, so the
 * binding lands here. `RemoteModule` publishes the qualified slot with
 * `@BindsOptionalOf`, so the remote module still assembles on its own with
 * `NoOpSecretResolver` when this one is absent; `DataModule` fills the slot
 * whenever the data layer is on the graph.
 *
 * ## Why a missing secret is null and not an error
 *
 * [SecretResolver.resolve] returns null for "no such secret", and this
 * implementation also returns null when the value is *unrecoverable* — the
 * Keystore key was invalidated by a lock-screen change, or the ciphertext came
 * off a restored backup. The request then goes out unauthenticated and the
 * server answers 401, which the UI already knows how to explain and offers
 * re-entry from. Throwing here instead would surface a credential problem as a
 * network fault at the least useful moment: inside an OkHttp interceptor, on a
 * background refresh, with no screen to prompt from.
 *
 * The loss is still logged, because "my server stopped accepting me after I
 * changed my PIN" is otherwise very hard to diagnose. `ref.alias` is safe to
 * log; the value it points at never leaves this call.
 */
@Singleton
class SecretResolverImpl
    @Inject
    constructor(
        private val secrets: SecretsStore,
    ) : SecretResolver {
        override suspend fun resolve(ref: SecretRef): String? = when (val result = secrets.get(ref)) {
            is SecretResult.Ok -> {
                result.value
            }

            is SecretResult.Unavailable -> {
                Timber.w("Credential %s can no longer be decrypted; sending unauthenticated.", ref.alias)
                null
            }

            is SecretResult.Failed -> {
                Timber.w("Credential %s could not be read: %s", ref.alias, result.error.message)
                null
            }
        }
    }
