plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.mzalmuzakki.gesitsmid"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.mzalmuzakki.gesitsmid"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Signing: if GESITS_KEYSTORE (path) and GESITS_KEYSTORE_PASSWORD are set (from GitHub
    // secrets, see .github/workflows/android.yml), the APK is signed with your own key.
    // Otherwise the standard Android debug key is used. No key is ever stored in this repo.
    val ownKey = System.getenv("GESITS_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ownKey != null) {
            create("own") {
                storeFile = ownKey
                storePassword = System.getenv("GESITS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("GESITS_KEY_ALIAS") ?: "gesits-mid"
                keyPassword = System.getenv("GESITS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
