# Liner Android ProGuard / R8 rules
-keep class fun.tryliner.android.core.model.** { *; }
-keep class fun.tryliner.android.core.crypto.** { *; }
-keep class fun.tryliner.android.core.lyrics.** { *; }
-keep class fun.tryliner.android.core.network.** { *; }

# Keep Chicory WASM runtime classes
-dontwarn com.dylibso.chicory.**
-keep class com.dylibso.chicory.** { *; }
