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
