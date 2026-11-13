plugins {
    id("com.android.application")
}

// «RetroStick Núcleos»: no code, only the emulator cores as native libraries. Android extracts them to this app's
// lib folder, which every app may read and load (like WebView's libraries), so RetroArch loads a core from there by
// path (the launcher passes it, see Cores.kt) and nobody has to install cores inside RetroArch.
// The .so files come from libretro's buildbot: `node fetch-cores.mjs` (not in git).
android {
    namespace = "com.retrostick.cores"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.retrostick.cores"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "2026.11.13"
        ndk { abiFilters += "armeabi-v7a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        // Extracted to disk (not loaded from inside the APK): RetroArch needs a real file path.
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/*.so" }
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}
