// 순수 Kotlin 모듈: 안드로이드 없이 PC에서 단위 테스트 가능 (./gradlew :engine:test)
// JDK 툴체인 고정 대신 바이트코드 타깃만 17로 지정 → Android Studio 내장 JDK 21로 바로 빌드됨
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies { testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }
