// Cast's media server (C1): the phone serves a prepared file over the home network to a Cast device, which fetches it
// with ranges. Pure JVM so it is tested over real sockets; it is in every build and runs on API 26, so it keeps to the
// JDK APIs Android has there (checkMinApi scans io.skjaere code in the release dex).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Item 6(b)'s spike stack (SpikeStack, its relay, faults and JSON), shared with the app's run through its adapter.
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Its tokens, the private-address rule and the request reader, shared with the setup page (L51, L52).
    implementation(project(":core:handoff"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The workstation's probes (ServeProbeTest, U2): -PserveProbe=<a folder holding probe.mkv and probe.mp4> runs FFmpeg and
// headless Chrome against files served on 127.0.0.1, every time and with its output shown; without it, it is skipped.
val serveProbe: String? = providers.gradleProperty("serveProbe").orElse(providers.systemProperty("serveProbe")).orNull

// Item 6(b)'s serving side (ServeSpikeTest, W21): -PserveSpike=<the workstation's home-network address> with
// -PserveSpikeMedia=<a folder of synthetic files> serves them to the Shield's Cast receiver until the harness stops it,
// with its log shown. The lead alone runs it, each casting session once the TV is free; without it, it is skipped.
val serveSpike: String? = providers.gradleProperty("serveSpike").orElse(providers.systemProperty("serveSpike")).orNull
val serveSpikeOptions = listOf("serveSpikeMedia", "serveSpikePeer", "serveSpikePage", "serveSpikeMinutes", "serveSpikeLog")

tasks.test {
    useJUnitPlatform()
    serveProbe?.let { systemProperty("serveProbe", it) }
    if (serveSpike != null) {
        systemProperty("serveSpike", serveSpike)
        for (option in serveSpikeOptions) providers.gradleProperty(option).orNull?.let { systemProperty(option, it) }
    }
    if (serveProbe != null || serveSpike != null) {
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
