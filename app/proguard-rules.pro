# Typesake Android: keep IME service + JNI bridge, Compose UI is referenced reflectively only.
-keep class com.typesake.app.TypesakeCore { *; }
-keep class com.typesake.app.TypesakeImeService { *; }
-keep class com.typesake.app.MainActivity { *; }
-keep class com.typesake.app.HubActivity { *; }
