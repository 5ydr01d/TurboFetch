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
    namespace = "com.fiveydr.turbofetch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fiveydr.turbofetch"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Local builds keep Android's standard debug key. CI can provide a
    // persistent keystore so APKs from different workflow runs can update
    // one another instead of being signed by runner-specific keys.
    signingConfigs {
        getByName("debug") {
            if (!ciDebugKeystorePath.isNullOrBlank()) {
                storeFile = file(ciDebugKeystorePath)
                storePassword = requireNotNull(ciDebugStorePassword) {
                    "TURBOFETCH_DEBUG_STORE_PASSWORD is required with the CI keystore."
                }
                keyAlias = requireNotNull(ciDebugKeyAlias) {
                    "TURBOFETCH_DEBUG_KEY_ALIAS is required with the CI keystore."
                }
                keyPassword = requireNotNull(ciDebugKeyPassword) {
                    "TURBOFETCH_DEBUG_KEY_PASSWORD is required with the CI keystore."
                }
            }
        }
    }

    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
