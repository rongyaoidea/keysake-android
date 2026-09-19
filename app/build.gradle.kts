plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.typesake.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.typesake.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            // cargo-ndk 产物目录（CI 写入）：app/src/main/jniLibs/<abi>/libtypesake_core.so
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
}

// 可选：本地若装了 cargo-ndk 且 CI 还没编过 .so，则 preBuild 自动编 Rust；
// 缺工具或已有 .so 时直接跳过，绝不阻塞 Gradle。
tasks.register<Exec>("buildRustDebug") {
    commandLine(
        "sh", "-c",
        "if ls src/main/jniLibs/*/libtypesake_core.so >/dev/null 2>&1; then " +
            "echo '[typesake] jniLibs present, skip rust build'; " +
            "elif command -v cargo-ndk >/dev/null 2>&1; then " +
            "cd ../rust-core && cargo ndk -o ../app/src/main/jniLibs -t armeabi-v7a -t arm64-v8a -t x86_64 build; " +
            "else echo '[typesake] cargo-ndk not found, skip rust build (CI builds it)'; fi"
    )
    workingDir = projectDir
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("buildRustDebug")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.ui.tooling)
}
