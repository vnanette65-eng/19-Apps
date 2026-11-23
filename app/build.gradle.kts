plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.nationhub.appvault"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nationhub.appvault"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "APPVAULT_BASE_URL", "\"https://REPLACE-WITH-PUBLISHED-APPVAULT-URL\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
