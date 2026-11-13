// Host-side build tool behind :app:checkNoDiscover, which fails when the release (Play) build carries anything of Discover
// (only the debug, bench and beta builds have it) or when the debug build lacks what it looks for, and behind
// :app:checkBetaHasNoDevSettings (SecretCheck). Never packaged into the app.
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
