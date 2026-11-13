# Release builds do not minify (see app/build.gradle). Kept for the day they do.
-keep class com.termux.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.**
