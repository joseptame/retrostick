plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.retrostick.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.retrostick.launcher"
        // Font resources and Resources.getFont() need API 26; the stick runs API 28.
        minSdk = 26
        // Sideloaded on Android 9: targeting 28 keeps legacy external storage access.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideloaded app: signed with the local debug key unless a real one is configured.
            signingConfig = signingConfigs.getByName("debug")
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
        // targetSdk 28 is intentional (see above); not published on Google Play.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}
