// Host-side developer tool: seeds the mock NNTP server with a file split into realistic
// multipart yEnc articles, writes a matching .nzb, and can verify the round trip through
// nzb-streamer on the JVM. Never packaged into the app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.nzb.streamer)
    implementation(libs.kotlin.compression.utils)
    implementation(libs.rapidyenc.wrapper)
    implementation(libs.kotlinx.coroutines.core)
    // The README screens catalogue (indexer --catalog screens) is JSON.
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.slf4j.simple)

    // The fake indexer is tested with the app's own client, and the debrid fake with the app's service clients.
    testImplementation(project(":core:newznab"))
    testImplementation(project(":core:debrid"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // ScreensCatalogTest reads the screens catalogue from the app's androidTest assets.
    inputs.file(rootProject.file("app/src/androidTest/assets/screens/catalog.json"))
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("screensCatalog")
}

application {
    mainClass = "io.skjaere.usenet.mockseed.MainKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
}
