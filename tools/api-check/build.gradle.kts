// Host-side build tool behind :app:checkMinApi: lists io.skjaere code in the app's dex that
// uses JDK APIs Android only added after minSdk, or lacks altogether. Never packaged into the app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.asm)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
