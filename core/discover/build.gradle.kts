// Discover's lists from TMDB (trending, popular, top rated, ...), their filters, and the source the app's Discover
// screens read them from. Only the app's debug, bench and beta builds depend on this module, so the release (Play) build
// carries none of it: the app's checkNoDiscover fails the release build if any of it leaks in.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // TmdbClient.fetch: the user's key, language, cache and errors, shared with the rest of the app.
    api(project(":core:metadata"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.client.mock)
    // The release guard's own list of TMDB's paths, which the literal-path test checks every path asked for against.
    testImplementation(project(":tools:feature-check"))
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}
