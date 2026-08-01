# Consumer ProGuard rules for :server
#
# Ktor resolves its engine and plugins through ServiceLoader and reflection, so
# R8 cannot see the references. These rules are the difference between a
# working embedded server and a release build that throws
# "Failed to find HTTP server engine implementation" on first start.
-keep class io.ktor.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**

# Ktor logs through SLF4J; the binder is discovered by name.
-dontwarn org.slf4j.**
-keepnames class org.slf4j.impl.StaticLoggerBinder

# Route DTOs are serialized by kotlinx.serialization.
-keepclassmembers class **$$serializer { *** descriptor; }
