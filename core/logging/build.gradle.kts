plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.skjaere.usenet.logging"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // android.jar's Log is a stub in JVM tests: the logger's tests capture what would reach logcat, and
    // anything else logged goes nowhere rather than failing "not mocked".
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.all { it.useJUnitPlatform() }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.slf4j.api)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
