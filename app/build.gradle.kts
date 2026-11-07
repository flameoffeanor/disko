plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.burak.disko"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.burak.disko"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Sabit imza: her derleme aynı anahtarla imzalanır, güncellemeler eskisinin üstüne kurulur
    signingConfigs {
        getByName("debug") {
            storeFile = file("disko.keystore")
            storePassword = "disko123"
            keyAlias = "disko"
            keyPassword = "disko123"
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
