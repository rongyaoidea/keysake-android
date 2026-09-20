import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** 版本可由 CI 通过 -PversionName=... -PversionCode=... 覆盖（发布 workflow 用 tag）。 */
val versionNameOverride: String = (project.findProperty("versionName") as? String) ?: "0.3.1"
val versionCodeOverride: Int = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 4

android {
    namespace = "com.typesake.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.typesake.app"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeOverride
        versionName = versionNameOverride
        // 只打包我们真正构建的 ABI，避免依赖带入 x86 等杂项 .so
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            signingProperties()?.let { props ->
                storeFile = File(props.getProperty("keystoreFile", ""))
                storePassword = props.getProperty("keystorePassword", "")
                keyAlias = props.getProperty("keyAlias", "")
                keyPassword = props.getProperty("keyPassword", "")
                // v1 + v2：v1 让任何工具（keytool/unzip）都能核对签名证书
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingProperties()?.let { props ->
                val storePath = props.getProperty("keystoreFile")
                if (!storePath.isNullOrEmpty() && File(storePath).exists()) {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
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
    packaging {
        // Rust .so 体积大（每 ABI ~10MB），压缩后 APK 小很多（安装时解压）
        jniLibs {
            useLegacyPackaging = true
        }
    }
    lint {
        // CI 里跑 lint 并出报告，但不因 lint 阻断构建（本机无法迭代 Android 代码）
        abortOnError = false
        checkReleaseBuilds = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * 本地若无 cargo-ndk 且 CI 也没编 .so，则跳过，绝不阻塞 Gradle。
 * 有 .so（CI 先编好）时直接复用。
 */
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
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
}

/** 签名配置：优先环境变量（CI secret），其次本地 signing.properties；都没有则不签名。 */
fun signingProperties(): Properties? {
    val fromEnv = System.getenv("ANDROID_KEYSTORE_PATH")
    if (!fromEnv.isNullOrEmpty() && File(fromEnv).exists()) {
        return Properties().apply {
            setProperty("keystoreFile", fromEnv)
            setProperty("keystorePassword", System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: "")
            setProperty("keyAlias", System.getenv("ANDROID_KEY_ALIAS") ?: "")
            setProperty("keyPassword", System.getenv("ANDROID_KEY_PASSWORD") ?: "")
        }
    }
    val file = listOf(File("$projectDir/signing.properties"), File("$rootDir/signing.properties"))
        .firstOrNull { it.exists() } ?: return null
    return Properties().apply { file.inputStream().use { load(it) } }
}
