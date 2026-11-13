import java.util.Properties

// Debrid services (D1): one service interface (L195) and a client per service, over Ktor with OkHttp and lenient
// kotlinx.serialization models. Pure JVM so each client is tested with Ktor's MockEngine (L190); it runs on API 26, so it
// keeps to the JDK APIs Android has there (checkMinApi scans io.skjaere code in the release dex).
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
    // A prepared release has a serialised form (the app's listing store keeps it, L181).
    api(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.simple)
}

// The live-account classes (L212, W77; TorBoxLiveTest, PremiumizeLiveTest), the lead's alone: -PdebridLive=true has this
// task read your own keys (devTorBoxKey, devPremiumizeKey) from the root's local.properties as it runs, and only then, and
// put them in the test JVM's environment with the openly licensed film's hash (-PdebridLiveHash=<40 hex>). Never as
// system properties: --info prints those on the test JVM's command line, and the environment no log of Gradle's prints.
// The switch and where the reports go (build/reports/debridLive) are system properties. Set as the task starts, the keys
// are never among its inputs; the task is never up to date. Without the property nothing is read and the classes skip.
// TorBoxRemoveLiveTest deletes an entry from the account, so it needs -PdebridLiveRemove=true as well (L253): a
// -PdebridLive=true run without a --tests filter still leaves it out.
val debridLive = providers.gradleProperty("debridLive").orNull == "true"
val debridLiveRemove = providers.gradleProperty("debridLiveRemove").orNull == "true"
val debridLiveHash: String? = providers.gradleProperty("debridLiveHash").orNull

tasks.test {
    useJUnitPlatform()
    if (debridLive) {
        val test = this
        val localProperties = rootProject.file("local.properties")
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
        systemProperty("debridLive", "true")
        if (debridLiveRemove) systemProperty("debridLiveRemove", "true")
        systemProperty("debridLiveReports", layout.buildDirectory.dir("reports/debridLive").get().asFile.path)
        doFirst {
            val local = Properties().apply { if (localProperties.exists()) localProperties.reader().use { load(it) } }
            listOf("devTorBoxKey", "devPremiumizeKey").forEach { name -> local.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { test.environment(name, it) } }
            debridLiveHash?.let { test.environment("debridLiveHash", it) }
        }
    }
}
