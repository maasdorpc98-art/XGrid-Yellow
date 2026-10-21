plugins {
    id("com.android.application")
}

android {
    namespace = "com.xgrid.yellownative"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xgrid.yellownative"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Stage 1 intentionally contains no external runtime dependencies.
// Stage 2 will add MediaPipe Tasks Vision plus an on-device model.
