// Vendored from termux/termux-app v0.118.3: the terminal-emulator and
// terminal-view libraries (Apache License 2.0; see NOTICE). Built here so the
// PTY library uses our NDK and 16 KB page alignment.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"
    compileSdk = 37
    ndkVersion = "29.0.14206865"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
}
