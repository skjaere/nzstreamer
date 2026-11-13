import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.time.Duration

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// The usenet libraries' releases pinned in gradle/libs.versions.toml win wherever a library's POM names another version
// of one of them, so no earlier tag (a Java 25 build) gets in. With -PusenetLibsDir a local checkout still replaces its
// library, whatever the version.
val usenetPins = mapOf(
    "nzb-streamer" to libs.versions.nzb.streamer,
    "ktor-nntp-client" to libs.versions.ktor.nntp.client,
    "kotlin-compression-utils" to libs.versions.kotlin.compression.utils,
    "rapidyenc-kotlin-wrapper" to libs.versions.rapidyenc.wrapper,
).mapValues { (_, version) -> version.get() }

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.github.skjaere") {
                usenetPins[requested.name]?.let { pin ->
                    useVersion(pin)
                    because("the usenet libraries' release pinned in gradle/libs.versions.toml")
                }
            }
        }
    }
}

// Each JVM test has 2 minutes, and so has each @BeforeAll, @BeforeEach, @AfterEach and @AfterAll. The slowest test takes
// about 15 s on CI. A test runs on a thread of its own: at the limit JUnit gives up on that thread and fails the test with
// the thread's stack, and the tests after it still run. So a hang names its test and the line its thread waits at. For a
// test in runBlocking, that is its runBlocking line: the stack doesn't show the call the coroutine is suspended in. The
// limit is off while a debugger is attached. A test task has 30 minutes: that limit catches a hang outside any test. A
// failure's whole stack goes to the build's output, so CI's log shows it. The runs by hand that a -P property turns on
// (serveSpike, serveProbe, debridLive, linkPeer) take as long as they need: their modules turn both limits off for them.
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("junit.jupiter.execution.timeout.default", "2m")
        systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
        timeout.set(Duration.ofMinutes(30))
        testLogging.exceptionFormat = TestExceptionFormat.FULL
    }
}
