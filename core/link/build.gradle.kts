// Play on TV's link: mutual TLS with pinned keys between a phone and NZStreamer on the TV, its frames, messages and
// pairing. Pure JVM so it is tested over real sockets; it runs on API 26, so it keeps to the JDK APIs Android has there
// (checkMinApi scans io.skjaere code in the release dex).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The link's messages are JSON (L5); the app has the same version.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The workstation's side of a device test that listens for a peer (LinkPeerClientTest, L14): -PlinkPeer=<address>[:port]
// runs it against that device, every time and with its output shown; without it, it is skipped.
val linkPeer: String? = providers.gradleProperty("linkPeer").orElse(providers.systemProperty("linkPeer")).orNull

tasks.test {
    useJUnitPlatform()
    if (linkPeer != null) {
        systemProperty("linkPeer", linkPeer)
        // A run by hand takes as long as it needs, so the root build's limits are off for it.
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled")
        timeout.unset()
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed")
        }
    }
}
