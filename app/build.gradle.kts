import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing creds live in keystore.properties (gitignored, never committed).
// Absent (e.g. CI without secrets) → release stays unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "al.basecode.jarvistv"
    compileSdk = 34

    defaultConfig {
        applicationId = "space.construct.tv"   // ConstructTV bundle id
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
        // ConstructTV backend. Production = tv.construct.space; override via remote MENU.
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"https://tv.construct.space\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
