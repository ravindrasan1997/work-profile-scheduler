# R8 / ProGuard rules for the minified release build.
#
# The app's own code is tiny; the size win comes from R8 stripping unused Compose/AndroidX
# library code, not from shrinking our classes. So we keep ALL app classes to avoid any
# reflection breakage (manifest receivers/services, DataStore, kotlinx-serialization).
-keep class com.worksched.** { *; }

# kotlinx.serialization — keep generated serializers for @Serializable models.
# (The library ships its own consumer rules, but this is a belt-and-suspenders keep.)
-keepclassmembers class com.worksched.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.worksched.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Compose + AndroidX bring their own consumer ProGuard rules; nothing extra needed.
-dontwarn org.jetbrains.annotations.**
