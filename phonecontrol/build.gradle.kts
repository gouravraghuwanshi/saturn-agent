import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// "Phone Control": an MCP server that lets AI agents on this phone read the screen and
// tap/type in other apps, through an accessibility service. A separate app on purpose:
// any MCP client can use it, and Saturn itself stays free of the Accessibility API.
android {
    namespace = "io.github.pimobile.phonecontrol"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.gouravraghuwanshi.phonecontrol"
        minSdk = 30 // takeScreenshot + IME enter
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures { compose = true }
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
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
}
