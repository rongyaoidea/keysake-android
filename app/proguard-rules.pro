# Typesake: keep everything app-owned (IME service, activities, JNI bridge).
-keep class com.typesake.app.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
