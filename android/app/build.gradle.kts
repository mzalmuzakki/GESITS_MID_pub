import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version lives in android/version.properties (one place to edit per release).
val ver = Properties().apply { rootProject.file("version.properties").inputStream().use { stream -> load(stream) } }
val vMajor = ver.getProperty("major").trim().toInt()
val vXx = ver.getProperty("xx").trim().toInt()
val vYy = ver.getProperty("yy").trim().toInt()

android {
    namespace = "io.github.mzalmuzakki.gesitsmid"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.mzalmuzakki.gesitsmid"
        minSdk = 26
        targetSdk = 34
        versionCode = vMajor * 10000 + vXx * 100 + vYy
        versionName = String.format("%d.%02d.%02d", vMajor, vXx, vYy)   // shown as v0.10.00
    }

    // Signing: if GESITS_KEYSTORE (path) and GESITS_KEYSTORE_PASSWORD are set (from GitHub
    // secrets, see .github/workflows/android.yml), the APK is signed with your own key.
    // Otherwise the standard Android debug key is used. No key is ever stored in this repo.
    val ownKey = System.getenv("GESITS_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ownKey != null) {
            create("own") {
                storeFile = ownKey
                storeType = "pkcs12"   // made by scripts/make_signing_key.py
                storePassword = System.getenv("GESITS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GESITS_KEY_ALIAS") ?: "gesits-mid"
                keyPassword = System.getenv("GESITS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8: removes unused code and resources -> smaller APK, less RAM to load it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (ownKey != null) signingConfigs.getByName("own") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}

// No app dependencies on purpose: only the Android framework and the Kotlin standard library.
// (Test-only libraries below are used by unit tests on the build machine and are NOT in the APK.)
dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.24")
}
