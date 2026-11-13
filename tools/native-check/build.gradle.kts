// Host-side build tool behind :app:checkNativeLibs and :app:checkBundleNativeLibs: checks that the
// app's APK, or its App Bundle, carries every native library for each ABI and that 64-bit ones load
// on 16 KB page devices. Never packaged into the app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
