# OkHttp uses reflection for optional platform integrations.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Android framework instantiates these components by class name.
-keep class ai.arena.assist.service.** { *; }
-keep class ai.arena.assist.voice.** { *; }
-keep class ai.arena.assist.receiver.** { *; }
