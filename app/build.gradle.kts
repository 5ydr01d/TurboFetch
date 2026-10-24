plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciDebugKeystorePath = providers.environmentVariable("TURBOFETCH_DEBUG_KEYSTORE_PATH").orNull
val ciDebugStorePassword = providers.environmentVariable("TURBOFETCH_DEBUG_STORE_PASSWORD").orNull
val ciDebugKeyAlias = providers.environmentVariable("TURBOFETCH_DEBUG_KEY_ALIAS").orNull
val ciDebugKeyPassword = providers.environmentVariable("TURBOFETCH_DEBUG_KEY_PASSWORD").orNull

android {
    namespace = "com.example"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fiveydr.turbofetch"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.4.6"
    }

    // Build one APK per ABI so each phone installs only its own native FFmpeg/Python
    // libraries. All supported ABIs remain available as separate downloadable artifacts.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }

    signingConfigs {
        getByName("debug") {
            if (!ciDebugKeystorePath.isNullOrBlank()) {
                storeFile = file(ciDebugKeystorePath)
                storePassword = requireNotNull(ciDebugStorePassword)
                keyAlias = requireNotNull(ciDebugKeyAlias)
                keyPassword = requireNotNull(ciDebugKeyPassword)
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

}


dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Downloader-only video page extraction via yt-dlp; no browser UI is packaged.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")


    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
