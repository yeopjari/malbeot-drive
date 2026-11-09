plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.malbeot.drive"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.malbeot.drive"
        minSdk = 28          // Pleos Connect 최소 요구
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":engine"))
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    // TODO NaviHelper SDK 아티팩트명·버전 확인 후 추가 (문서상 v3.0.5)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
