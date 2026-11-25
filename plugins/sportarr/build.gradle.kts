// The Sportarr plugin (L639): a Sports tab with the leagues, teams and sessions the user follows in their own Sportarr. The
// root build's plugin convention builds it as every plugin, its JVM tests and its debug APK for the app's device tests
// among it; this file names its plugins and its tests' one dependency of their own.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // The fake Sportarr, the sports indexer and their shared catalogue, for the tests against the fakes (L655). Test-only,
    // so it never reaches the APK, whose rules plugin-check holds as before. Without mock-seed's run-time slf4j-simple: the
    // logs test's capturing provider must be the only one (L653).
    testImplementation(project(":tools:mock-seed")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
}
