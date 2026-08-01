package io.github.jaypetez.ollamamobile.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * "This app contains no analytics and no crash reporting SaaS" is a claim the
 * settings screen makes to the user in as many words. This test is what makes
 * it true rather than intended.
 *
 * ## Why a classpath assertion and not a code review
 *
 * Nobody adds Firebase Analytics on purpose. It arrives as a transitive
 * dependency of something innocuous — a Play Services artifact, an
 * in-app-update library, a vendor SDK — and it initialises itself from a
 * `ContentProvider` merged in from its own manifest, so it starts collecting
 * before a single line of our code runs. Reviewing our own source cannot catch
 * that. Only asking "is the class on the runtime classpath" can.
 *
 * A hit here is not necessarily malicious; it is a decision that must be made
 * deliberately. If one of these ever appears, the fix is an `exclude` in the
 * offending dependency, not a deletion from this list.
 */
class NoTelemetryClasspathTest {
    @Test
    fun `no analytics or crash-reporting SDK is on the runtime classpath`() {
        val found = FORBIDDEN.filter { it.isLoadable() }

        assertThat(found).isEmpty()
    }

    @Test
    fun `the probe itself works`() {
        // A test that can only pass is worth nothing. If isLoadable() were
        // broken — a swallowed exception, a wrong classloader — the assertion
        // above would pass for the wrong reason, forever.
        assertThat("java.lang.String".isLoadable()).isTrue()
        assertThat("com.example.definitely.NotOnTheClasspath".isLoadable()).isFalse()
    }

    private fun String.isLoadable(): Boolean = try {
        // initialize = false: merely asking the question must not run a static
        // initialiser, which for several of these would be the very thing we
        // are trying to prove does not happen.
        Class.forName(this, false, NoTelemetryClasspathTest::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: NoClassDefFoundError) {
        false
    }

    private companion object {
        val FORBIDDEN = listOf(
            // Analytics
            "com.google.firebase.analytics.FirebaseAnalytics",
            "com.google.android.gms.analytics.GoogleAnalytics",
            "com.google.android.gms.measurement.AppMeasurement",
            "com.mixpanel.android.mpmetrics.MixpanelAPI",
            "com.amplitude.api.Amplitude",
            "com.segment.analytics.Analytics",
            "com.posthog.PostHog",
            "com.appsflyer.AppsFlyerLib",
            "com.facebook.appevents.AppEventsLogger",
            // Crash reporting as a service
            "com.google.firebase.crashlytics.FirebaseCrashlytics",
            "com.crashlytics.android.Crashlytics",
            "io.sentry.Sentry",
            "com.bugsnag.android.Bugsnag",
            "io.embrace.android.embracesdk.Embrace",
            "com.datadog.android.Datadog",
            "com.instabug.library.Instabug",
            // The Play Services bootstrap that pulls measurement in transitively
            "com.google.android.gms.measurement.internal.zzhx",
        )
    }
}
