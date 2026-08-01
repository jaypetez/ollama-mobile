package io.github.jaypetez.ollamamobile.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumentation runner that swaps in [HiltTestApplication] so androidTest
 * classes can replace Hilt modules.
 *
 * Referenced by `testInstrumentationRunner` in the application convention
 * plugin. It lives in `androidTest` because that is where it is packaged —
 * putting it in `main` would ship the test dependencies in the release APK.
 */
class OllamaMobileTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(classLoader, HiltTestApplication::class.java.name, context)
}
