plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Android modules swap the JNA jar for the AAR; this pure-JVM module keeps the jar so
    // it can be unit-tested on the host.
    api(libs.nzb.streamer)
    api(libs.kotlin.compression.utils)
    api(libs.kotlinx.coroutines.core)
    api(project(":core:releases"))
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}
