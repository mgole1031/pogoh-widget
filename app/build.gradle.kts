plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.gole.pogoh"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.gole.pogoh"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Fixed key so every CI build can install over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("pogoh-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party dependencies: HttpURLConnection + org.json + LocationManager are all built into Android.
