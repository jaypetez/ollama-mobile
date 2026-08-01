# Consumer ProGuard rules for :core-remote
#
# Every wire type is a @Serializable class whose generated serializer is looked
# up reflectively. Without these, release builds fail at the first API call
# with "Serializer for class 'X' is not found".
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations

-keepclassmembers class **$$serializer { *** descriptor; }

-if @kotlinx.serialization.Serializable class io.github.jaypetez.ollamamobile.remote.dto.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}

-if @kotlinx.serialization.Serializable class io.github.jaypetez.ollamamobile.remote.dto.** {
    static **$* *;
}
-keepclassmembers class <2> {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn okhttp3.**
-dontwarn okio.**
