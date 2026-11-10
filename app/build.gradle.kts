plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ai.malbeot.drive"
    compileSdk = 35
    useLibrary("android.car")   // AAOS Car API (차속·기어)
    defaultConfig {
        applicationId = "ai.malbeot.drive"
        minSdk = 33          // NaviHelper 공식 샘플 기준(Pleos Connect 에뮬레이터는 API 34)
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
    implementation("ai.pleos.playground:NaviHelper:3.0.5")   // 공식 샘플 pleos-sample-navi-app 기준
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
