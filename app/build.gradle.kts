import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "io.github.pimobile"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = (findProperty("appId") as String?) ?: "io.github.gouravraghuwanshi.saturn" // -PappId=… for the old dev install
        minSdk = 29
        targetSdk = 37
        versionCode = 19
        versionName = "1.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    // Release signing: keystore.properties lives outside the repo (see RELEASING.md).
    val signing = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?: file(System.getProperty("user.home") + "/saturn-keys/keystore.properties").takeIf { it.exists() }
    signingConfigs {
        if (signing != null) create("release") {
            val p = Properties().apply { signing.inputStream().use { load(it) } }
            storeFile = file(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false // reflection + JSON everywhere; size is dominated by the runtime anyway
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures { compose = true; buildConfig = true }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    packaging {
        // Extract .so files to nativeLibraryDir on install: that directory is
        // the one place a modern (targetSdk 29+) app may exec() binaries from.
        jniLibs.useLegacyPackaging = true
        // gh is downloaded on first use instead (runtime/GhTool.kt): 14 MB less to download.
        jniLibs.excludes += "**/libx_gh.so"
        // Compress the app's code in the APK: ~18 MB smaller download, same app.
        dex.useLegacyPackaging = true
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
    // Unit tests run on the JVM, where Android's org.json is only a stub: use the real one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(project(":terminal"))
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    // AppFunctions: Saturn's actions for Gemini and other approved assistants (Android 16+).
    implementation("androidx.appfunctions:appfunctions:1.0.0-rc01")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-rc01")
}
