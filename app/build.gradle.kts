import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appProps = Properties().apply {
    load(FileInputStream(rootProject.file("app.properties")))
}
val appName: String = appProps.getProperty("app.name")
val appPackage: String = appProps.getProperty("app.package")

// versionCode/versionName are normally passed in from CI as project properties
// (-PappVersionCode=... -PappVersionName=...), derived from the run number and
// release tag respectively. Falls back to app.properties for local/dev builds
// where nobody passes those flags — versionCode 1 is fine locally since it's
// never uploaded anywhere.
val ciVersionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull()
val ciVersionName = project.findProperty("appVersionName") as String?

android {
    namespace = appPackage
    compileSdk = 36

    defaultConfig {
        applicationId = appPackage
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 1
        versionName = ciVersionName ?: error("appVersionName must be set via -PappVersionName")
        resValue("string", "app_name", appName)

        // Never hardcode/commit the key. Pass it at build time with
        // -PelevationApiKey=... or the ELEVATION_API_KEY env var. Empty by
        // default, in which case the app falls back to raw uncorrected
        // barometer readings (see MainActivity.processSession).
        val elevationApiKey = (project.findProperty("elevationApiKey") as String?)
            ?: System.getenv("ELEVATION_API_KEY")
            ?: ""
        buildConfigField("String", "ELEVATION_API_KEY", "\"$elevationApiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.create("release") {
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        resValues = true
        buildConfig = true
    }
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
    implementation(libs.androidx.appcompat)
    implementation(libs.play.services.location)
    debugImplementation(libs.androidx.ui.tooling)
}
