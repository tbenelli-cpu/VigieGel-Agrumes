plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.vigiegel.agrumes"
    compileSdk = 34

        signingConfigs {
        create("release") {
            storeFile = file("../vigiegel.keystore")
            storePassword = "vigiegel2026"
            keyAlias = "vigiegel"
            keyPassword = "vigiegel2026"
        }
        getByName("debug") {
            storeFile = file("vigiegel.keystore")
            storePassword = "vigiegel91"
            keyAlias = "vigiegel"
            keyPassword = "vigiegel91"
        }
    }

    defaultConfig {
        applicationId = "fr.vigiegel.agrumes"
        minSdk = 26
        targetSdk = 34
        versionCode = 16
        versionName = "1.12"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
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
}

dependencies {
}
