plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.offlinetalks"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.offlinetalks"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.0-alpha"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
