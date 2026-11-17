plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.vigiegel.agrumes"
    compileSdk = 34

    signingConfigs {
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
        versionCode = 10
        versionName = "1.7.2"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
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
