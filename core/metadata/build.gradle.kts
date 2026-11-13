plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.ktor.client.core)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
    // Live check against api.themoviedb.org; runs only when a key is passed:
    // ./gradlew :core:metadata:test -PtmdbKey=...  (or devTmdbKey in local.properties)
    val key = providers.gradleProperty("tmdbKey").orNull
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("devTmdbKey=") }?.substringAfter('=')?.trim()
    if (!key.isNullOrBlank()) environment("TMDB_KEY", key)
}
