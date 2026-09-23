plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.kakkoi.localai.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.kakkoi.localai.probe"
        // 29 matches the real app: it is the release that blocked exec() from
        // an app's data directory, which is what shapes that design.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
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

// No dependencies at all — not even AndroidX. Every library is something that
// could fail for a reason unrelated to the question being asked.
dependencies { }
