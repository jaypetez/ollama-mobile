# Consumer ProGuard rules for :core-storage
#
# Room generates a `*_Impl` subclass of the database and instantiates it
# reflectively by name, so the no-arg constructor must survive R8 full mode.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Entities and DAOs are referenced from generated code that R8 can see, but
# @TypeConverter methods are resolved by signature at runtime.
-keepclassmembers class io.github.jaypetez.ollamamobile.storage.converter.** {
    @androidx.room.TypeConverter <methods>;
}

# The bundled SQLite driver loads its native library by name.
-keep class androidx.sqlite.driver.bundled.** { *; }
