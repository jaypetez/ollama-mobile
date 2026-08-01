# Consumer ProGuard rules for :core-llm
#
# The JNI layer binds with RegisterNatives in JNI_OnLoad, which is immune to
# renaming. This rule covers any method that is bound by name instead, and any
# type the native side constructs or reads fields from.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keep class io.github.jaypetez.ollamamobile.llm.internal.** { *; }
