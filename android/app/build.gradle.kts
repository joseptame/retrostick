import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Google OAuth client ("TVs and Limited Input devices", docs/google-cloud.md). Kept out of git:
// android/local.properties locally, environment variables in CI.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun oauthValue(key: String, env: String) = localProps.getProperty(key) ?: System.getenv(env) ?: ""

android {
    namespace = "com.retrostick.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.retrostick.launcher"
        // Font resources and Resources.getFont() need API 26; the stick runs API 28.
        minSdk = 26
        // Sideloaded on Android 9: targeting 28 keeps legacy external storage access.
        targetSdk = 28
        versionCode = 21
        versionName = "1.5.10"
        buildConfigField("String", "OAUTH_CLIENT_ID", "\"${oauthValue("retrostick.clientId", "RETROSTICK_CLIENT_ID")}\"")
        // RetroShop: the webapp whose catalog it shows (paired by QR, see api/pair.ts).
        buildConfigField("String", "WEBAPP_URL", "\"https://retrostick.vercel.app\"")
        buildConfigField("String", "OAUTH_CLIENT_SECRET", "\"${oauthValue("retrostick.clientSecret", "RETROSTICK_CLIENT_SECRET")}\"")
    }

    buildFeatures {
        buildConfig = true
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
