import androidx.room.gradle.RoomExtension
import internal.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("androidx.room")

        extensions.configure<RoomExtension> {
            // Schemas are committed so MigrationTestHelper can verify every
            // upgrade path. Never delete an old schema JSON.
            schemaDirectory("$projectDir/schemas")
        }

        dependencies {
            add("implementation", libs.findLibrary("room-runtime").get())
            add("implementation", libs.findLibrary("room-ktx").get())
            // BundledSQLiteDriver ships its own SQLite, which is the only way
            // to guarantee FTS5 is compiled in regardless of the OEM's system
            // SQLite build flags.
            add("implementation", libs.findLibrary("sqlite-bundled").get())
            add("ksp", libs.findLibrary("room-compiler").get())
            add("testImplementation", libs.findLibrary("room-testing").get())
            add("androidTestImplementation", libs.findLibrary("room-testing").get())
        }
    }
}
