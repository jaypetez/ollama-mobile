package io.github.jaypetez.ollamamobile.common.net

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.model.NetworkPolicy
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class NetworkPolicyControllerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `defaults to LAN_ONLY so a cold start cannot leak`() = runTest {
        withController { controller, _ ->
            controller.awaitRestored()

            assertThat(controller.current).isEqualTo(NetworkPolicy.LAN_ONLY)
            assertThat(NetworkPolicyController.DEFAULT_POLICY).isEqualTo(NetworkPolicy.LAN_ONLY)
        }
    }

    /**
     * Backed by memory rather than a file on purpose. DataStore's atomic write
     * is `tmp.renameTo(target)`, and Windows `renameTo` refuses to clobber an
     * existing file, so a second write to the same path fails on a developer
     * machine while working fine on Android and on CI. Persistence is covered
     * by the round-trip test below, which writes each file exactly once; this
     * test is about what the controller publishes, not about the disk.
     */
    @Test
    fun `publishes the new policy to collectors`() = runTest {
        val scope = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
        val controller = NetworkPolicyController(InMemoryPreferences(), scope)
        controller.awaitRestored()

        controller.policy.test {
            assertThat(awaitItem()).isEqualTo(NetworkPolicy.LAN_ONLY)
            controller.setPolicy(NetworkPolicy.OFFLINE)
            assertThat(awaitItem()).isEqualTo(NetworkPolicy.OFFLINE)
            controller.setPolicy(NetworkPolicy.OPEN)
            assertThat(awaitItem()).isEqualTo(NetworkPolicy.OPEN)
        }
        scope.cancel()
    }

    private class InMemoryPreferences : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun `the setter is visible synchronously, before the disk write lands`() = runTest {
        withController { controller, _ ->
            controller.awaitRestored()

            controller.setPolicy(NetworkPolicy.OFFLINE)

            // LanOnlyGuard reads `current` from a non-suspending OkHttp callback,
            // so the in-memory value has to be authoritative the moment the
            // setter returns.
            assertThat(controller.current).isEqualTo(NetworkPolicy.OFFLINE)
        }
    }

    @Test
    fun `the choice survives process death`() = runTest {
        val file = File(temporaryFolder.newFolder(), "policy.preferences_pb")

        withController(file) { controller, _ ->
            controller.awaitRestored()
            controller.setPolicy(NetworkPolicy.OPEN)
        }

        // A second controller over the same file stands in for a cold start:
        // nothing is shared but the bytes on disk.
        withController(file) { restored, _ ->
            restored.awaitRestored()
            assertThat(restored.current).isEqualTo(NetworkPolicy.OPEN)
        }
    }

    @Test
    fun `an unrecognised stored value falls back to the safe default`() = runTest {
        val file = File(temporaryFolder.newFolder(), "policy.preferences_pb")

        withController(file) { controller, store ->
            controller.awaitRestored()
            store.edit { it[NetworkPolicyController.POLICY_KEY] = "SOMETHING_FROM_A_FUTURE_VERSION" }
        }

        withController(file) { restored, _ ->
            restored.awaitRestored()
            assertThat(restored.current).isEqualTo(NetworkPolicy.LAN_ONLY)
        }
    }

    /**
     * Runs [block] against a controller backed by a real DataStore, then tears
     * the store down. DataStore refuses two live instances over one file, so the
     * teardown is what makes the round-trip test above possible at all.
     */
    private suspend fun TestScope.withController(
        file: File = File(temporaryFolder.newFolder(), "policy.preferences_pb"),
        block: suspend (NetworkPolicyController, DataStore<Preferences>) -> Unit,
    ) {
        val scope = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        try {
            block(NetworkPolicyController(store, scope), store)
        } finally {
            scope.cancel()
            scope.coroutineContext.job.join()
        }
    }
}
