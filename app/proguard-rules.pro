# Keep everything from the UVC camera library (native methods + reflection targets)
-keep class com.jiangdg.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
