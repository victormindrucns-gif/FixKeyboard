plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fixkeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fixkeyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Same signing key on every build (created by the first GitHub build), so new versions install over the old one
    signingConfigs {
        create("fix") {
            storeFile = rootProject.file("keystore/fixkeyboard.jks")
            storePassword = "fixkeyboard"
            keyAlias = "fixkeyboard"
            keyPassword = "fixkeyboard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fix")
        }
        debug {
            signingConfig = signingConfigs.getByName("fix")
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
