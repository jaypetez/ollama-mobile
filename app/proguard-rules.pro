# ---------------------------------------------------------------------------
# OllamaMobile R8 configuration
#
# `android.enableR8.fullMode=true` is on, which is more aggressive than the
# legacy ProGuard defaults. Everything reached only by reflection, by a
# ServiceLoader, or across the JNI boundary must be kept explicitly.
#
# The release build is smoke-tested in CI (see .github/workflows/ci.yml) because
# these rules only fail at runtime, never at compile time.
# ---------------------------------------------------------------------------

# --- JNI -------------------------------------------------------------------
# The native layer binds with RegisterNatives in JNI_OnLoad, which is immune to
# renaming. This rule is belt-and-braces for any name-bound method that slips in.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Types constructed or read from native code.
-keep class io.github.jaypetez.ollamamobile.llm.internal.** { *; }

# --- kotlinx.serialization -------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *** descriptor; }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-if @kotlinx.serialization.Serializable class ** { static **$* *; }
-keepclassmembers class <2> {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Ktor server (CIO) -----------------------------------------------------
# Ktor discovers engines and plugins through ServiceLoader and reflection.
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-keepnames class org.slf4j.impl.StaticLoggerBinder

# --- OkHttp ----------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Hilt / Dagger ---------------------------------------------------------
-keep,allowobfuscation @interface dagger.hilt.**
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }

# --- Compose ---------------------------------------------------------------
-dontwarn androidx.compose.**

# --- Keep our own crash reports readable -----------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
