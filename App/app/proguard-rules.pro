# ProGuard rules for RADD Stream

# Keep Retrofit service interfaces
-keep interface com.raddstream.app.network.** { *; }

# Keep data models (Gson serialization)
-keep class com.raddstream.app.network.models.** { *; }

# Hilt
-keep class * extends dagger.hilt.android.internal.managers.ApplicationComponentManager { *; }

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Chromecast spike (E2 slice 3): the OptionsProvider is named only by the
# manifest meta-data string, so R8 cannot see the reference — keep it, or a
# release build silently loses Cast initialisation (debug builds do not
# minify, so this only bites releases).
-keep class com.raddstream.app.player.RaddCastOptionsProvider { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
