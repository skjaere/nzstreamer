// "Set up with your phone": the one-time page a TV serves on the home network, its form and its
// token. Pure JVM so the server is tested over real sockets; it runs on API 26, so it keeps to the
// JDK APIs Android has there (checkMinApi scans io.skjaere code in the release dex).
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
