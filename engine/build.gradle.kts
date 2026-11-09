// 순수 Kotlin 모듈: 안드로이드 없이 PC에서 단위 테스트 가능 (./gradlew :engine:test)
plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(kotlin("test")) }
tasks.test { useJUnitPlatform() }
