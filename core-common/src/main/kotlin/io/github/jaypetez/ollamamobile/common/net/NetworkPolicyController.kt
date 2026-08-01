package io.github.jaypetez.ollamamobile.common.net

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jaypetez.ollamamobile.common.dispatcher.ApplicationScope
import io.github.jaypetez.ollamamobile.common.dispatcher.IoDispatcher
import io.github.jaypetez.ollamamobile.model.NetworkPolicy
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns the current [NetworkPolicy] and persists it.
 *
 * The policy is exposed as a [StateFlow] rather than a `suspend fun get()`
 * because [LanOnlyGuard] has to read it from inside an OkHttp `Dns` lookup and
 * an `EventListener` callback — neither of which is a coroutine and neither of
 * which may block on disk. A `StateFlow.value` read is the only shape that
 * works at those call sites.
 */
@Singleton
class NetworkPolicyController
    @Inject
    constructor(
        @param:Named(NETWORK_POLICY_DATA_STORE) private val dataStore: DataStore<Preferences>,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        /**
         * Fails closed. Until the first read off disk completes, the app behaves
         * as if the user had chosen LAN-only. Defaulting to [NetworkPolicy.OPEN]
         * would mean that a request made in the first few milliseconds after a
         * cold start could leave the device even though the user had turned the
         * policy down — a security control that is off during startup is not a
         * security control.
         */
        private val mutablePolicy = MutableStateFlow(DEFAULT_POLICY)

        val policy: StateFlow<NetworkPolicy> = mutablePolicy.asStateFlow()

        private val loaded = MutableStateFlow(false)

        init {
            scope.launch {
                persisted().collect { restored ->
                    mutablePolicy.value = restored
                    loaded.value = true
                }
            }
        }

        /** The current policy, readable from a non-suspending context. */
        val current: NetworkPolicy get() = mutablePolicy.value

        suspend fun setPolicy(policy: NetworkPolicy) {
            // Published before the write so a request issued on the next line
            // already sees the stricter (or looser) rule. The disk write is the
            // durable copy, not the authority.
            mutablePolicy.value = policy
            dataStore.edit { preferences -> preferences[POLICY_KEY] = policy.name }
        }

        /**
         * Suspends until the persisted policy has been read at least once.
         *
         * Startup code that is about to make a request should await this so the
         * fail-closed default does not spuriously block a user who chose
         * [NetworkPolicy.OPEN].
         */
        suspend fun awaitRestored() {
            loaded.first { it }
        }

        private fun persisted() = dataStore.data
            .catch { throwable ->
                // A corrupt or unreadable preferences file must not take the
                // process down, and must not silently open the network either.
                if (throwable is IOException) {
                    Timber.w(throwable, "Network policy could not be read; using %s", DEFAULT_POLICY)
                    emit(emptyPreferences())
                } else {
                    throw throwable
                }
            }.map { preferences ->
                val stored = preferences[POLICY_KEY]
                NetworkPolicy.entries.firstOrNull { it.name == stored } ?: DEFAULT_POLICY
            }

        companion object {
            const val NETWORK_POLICY_DATA_STORE: String = "network-policy-data-store"

            val DEFAULT_POLICY: NetworkPolicy = NetworkPolicy.LAN_ONLY

            internal val POLICY_KEY = stringPreferencesKey("network_policy")

            internal const val FILE_NAME = "network_policy"
        }
    }

/**
 * The policy lives in its own small preferences file rather than in
 * `:core-storage`'s settings store.
 *
 * It has to: `:core-storage` depends on `:core-common`, so the dependency
 * cannot run the other way. Keeping it separate also means the one setting the
 * guard consults is not coupled to a migration of the general settings file.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkPolicyStoreModule {
    @Provides
    @Singleton
    @Named(NetworkPolicyController.NETWORK_POLICY_DATA_STORE)
    fun provideNetworkPolicyDataStore(
        @ApplicationContext context: Context,
        @IoDispatcher dispatcher: CoroutineDispatcher,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + dispatcher),
        produceFile = { context.preferencesDataStoreFile(NetworkPolicyController.FILE_NAME) },
    )
}
