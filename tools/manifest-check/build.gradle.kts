// Host-side build tool behind :app:checkTvManifest, which reads the app's merged release manifest for what a TV needs of
// it, libraries' entries included, and :app:checkCastStartsLate, which reads the merged manifests for what would start
// Google's Cast software without the app's gate. Never packaged into the app.
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
