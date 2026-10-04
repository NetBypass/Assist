# Keep OkHttp / okio internals used via reflection.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep our model / tool classes (reflection-free, but keep names for easier logcat debugging).
-keep class com.netbypass.assist.ai.** { *; }
-keep class com.netbypass.assist.tools.** { *; }
