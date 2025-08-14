// No shrinking/obfuscation needed for this simple module
# 关闭与 Xposed 相关的混淆（如果你以后启用 minify）
-keep class de.robv.android.xposed.** { *; }
-keep class com.example.batterytemp.** { *; }
