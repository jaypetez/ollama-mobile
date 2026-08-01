package io.github.jaypetez.ollamamobile.service

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.jaypetez.ollamamobile.data.engine.InferenceActivityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When the foreground service is allowed to exist.
 *
 * The rule is narrow and the cost of getting it wrong is asymmetric: starting
 * too eagerly puts a notification and a wake lock on a phone whose screen is
 * on and whose activity is already keeping the process alive, and stopping too
 * eagerly kills the answer the user is waiting for. Neither is visible in a
 * screenshot, so it is asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class InferenceServiceControllerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val tracker = InferenceActivityTracker()

    private fun controllerOn(owner: FakeLifecycleOwner, scope: CoroutineScope) =
        InferenceServiceController(context = context, activity = tracker, scope = scope)
            .also { it.start(owner) }

    private fun startedServiceName(): String? =
        shadowOf(context).nextStartedService?.component?.className

    private fun stoppedServiceName(): String? =
        shadowOf(context).nextStoppedService?.component?.className

    private fun drainStartedServices() {
        while (shadowOf(context).nextStartedService != null) {
            // Robolectric's queue is a queue; a stale entry from an earlier
            // assertion would otherwise satisfy the next one.
        }
    }

    private fun owner(state: Lifecycle.State) = FakeLifecycleOwner().apply { currentState = state }

    /**
     * A hand-rolled owner rather than `lifecycle-runtime-testing`.
     *
     * The artefact is not on this project's classpath, and adding a dependency
     * to drive one `LifecycleRegistry` is a worse trade than four lines here.
     * `createUnsafe` skips the main-thread assertion, which is what lets the
     * state be moved from a test coroutine.
     */
    private class FakeLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this)

        override val lifecycle: Lifecycle get() = registry

        var currentState: Lifecycle.State
            get() = registry.currentState
            set(value) {
                registry.currentState = value
            }
    }

    @Test
    fun `nothing starts while the app is on screen, even during a generation`() = runTest {
        val lifecycleOwner = owner(Lifecycle.State.RESUMED)
        controllerOn(lifecycleOwner, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        drainStartedServices()

        tracker.begin(modelLabel = "Qwen3 1.7B", isLocal = true, cancel = {})

        // A visible activity already keeps the process alive. A service here
        // would be a notification and a wake lock for nothing.
        advanceUntilIdle()
        assertThat(startedServiceName()).isNull()
    }

    @Test
    fun `nothing starts when the app goes away with nothing running`() = runTest {
        val lifecycleOwner = owner(Lifecycle.State.RESUMED)
        controllerOn(lifecycleOwner, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        drainStartedServices()

        lifecycleOwner.currentState = Lifecycle.State.CREATED

        advanceUntilIdle()

        assertThat(startedServiceName()).isNull()
    }

    @Test
    fun `the service starts when a generation is in flight and the app goes away`() = runTest {
        val lifecycleOwner = owner(Lifecycle.State.RESUMED)
        controllerOn(lifecycleOwner, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        drainStartedServices()
        tracker.begin(modelLabel = "Qwen3 1.7B", isLocal = true, cancel = {})

        lifecycleOwner.currentState = Lifecycle.State.CREATED

        advanceUntilIdle()

        assertThat(startedServiceName()).isEqualTo(InferenceForegroundService::class.java.name)
    }

    @Test
    fun `the service is stopped the moment the generation finishes`() = runTest {
        val lifecycleOwner = owner(Lifecycle.State.RESUMED)
        controllerOn(lifecycleOwner, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        val handle = tracker.begin(modelLabel = "Qwen3 1.7B", isLocal = true, cancel = {})
        lifecycleOwner.currentState = Lifecycle.State.CREATED
        drainStartedServices()

        tracker.end(handle)

        advanceUntilIdle()

        assertThat(stoppedServiceName()).isEqualTo(InferenceForegroundService::class.java.name)
    }

    @Test
    fun `the service is stopped when the app comes back to the foreground`() = runTest {
        val lifecycleOwner = owner(Lifecycle.State.RESUMED)
        controllerOn(lifecycleOwner, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        tracker.begin(modelLabel = "Qwen3 1.7B", isLocal = true, cancel = {})
        lifecycleOwner.currentState = Lifecycle.State.CREATED
        drainStartedServices()

        lifecycleOwner.currentState = Lifecycle.State.RESUMED

        // The activity keeps the process alive again, so the notification and
        // the wake lock have no further job to do.
        advanceUntilIdle()
        assertThat(stoppedServiceName()).isEqualTo(InferenceForegroundService::class.java.name)
    }

    @Test
    fun `the Stop handle cancels the generation rather than only hiding the notification`() = runTest {
        var cancelled = false
        tracker.begin(modelLabel = "Qwen3 1.7B", isLocal = true, cancel = { cancelled = true })

        tracker.cancelAll()

        advanceUntilIdle()

        assertThat(cancelled).isTrue()
    }

    @Test
    fun `cancelAll leaves the registration in place until the stream unwinds`() = runTest {
        // Clearing here would make the tracker claim to be idle while a
        // coroutine was still winding down, and the wake lock would go early.
        tracker.begin(modelLabel = "Qwen3 1.7B", isLocal = true, cancel = {})

        tracker.cancelAll()

        advanceUntilIdle()

        assertThat(tracker.active.value).hasSize(1)
    }
}
