plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.pimobile"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.pimobile.spike"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.0-dev"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { compose = true }

    packaging {
        // Extract .so files to nativeLibraryDir on install: that directory is
        // the one place a modern (targetSdk 29+) app may exec() binaries from.
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        // The bundles are already compressed.
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(project(":terminal"))
}
