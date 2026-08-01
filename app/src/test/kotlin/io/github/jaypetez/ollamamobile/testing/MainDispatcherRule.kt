package io.github.jaypetez.ollamamobile.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Points `Dispatchers.Main` at a test dispatcher for the duration of a test.
 *
 * Every view model here launches into `viewModelScope`, which is hard-wired to
 * `Dispatchers.Main.immediate`. Without this the first `launch` throws
 * "Module with the Main dispatcher had failed to initialize", and with a real
 * main dispatcher the assertions would race the coroutine.
 */
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
