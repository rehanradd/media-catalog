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

# NextLib FFmpeg decoder (library bump 2026-11-23): FfmpegLibrary is
# reached by reflection from FfmpegSupport (Class.forName), so R8 cannot
# trace that reference — keep the decoder package, or a release build
# silently loses the software decoder (debug builds do not minify, so
# this only bites releases). The renderer itself is referenced directly
# by NextRenderersFactory and survives without this rule; the keep is
# for the reflection-loaded library class and its JNI bridge.
-keep class io.github.anilbeesetti.nextlib.media3ext.** { *; }
-dontwarn io.github.anilbeesetti.nextlib.media3ext.**

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
