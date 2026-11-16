plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.pimobile"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.pimobile.spike"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1-spike"
        ndk { abiFilters += "arm64-v8a" }
    }

    packaging {
        // Extract .so files to nativeLibraryDir on install: that directory is
        // the one place a modern (targetSdk 29+) app may exec() binaries from.
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        // pi-bundle.zip is already compressed.
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
