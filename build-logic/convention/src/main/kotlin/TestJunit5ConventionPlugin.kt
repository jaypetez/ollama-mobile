import internal.configureJunit5
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * JUnit 5 (Jupiter, via the JUnit 6 platform BOM) for pure-JVM modules.
 *
 * Deliberately NOT applied to Android library modules. The community AGP plugin
 * that would be needed there lags AGP releases and Robolectric's runner is
 * still JUnit 4, so Android modules use JUnit 4 + Robolectric instead. Because
 * essentially all business logic lives in JVM modules, this costs no coverage.
 * See CONTRIBUTING.md ("Testing requirements") for which to use where.
 */
class TestJunit5ConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        configureJunit5()
    }
}
