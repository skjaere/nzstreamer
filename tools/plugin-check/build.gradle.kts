// Host-side build tool for plugins (L625): a plugin APK's rules, the link check against the app's dex, and the dumps of
// the plugin API and the UI kit. It reads a dex as :tools:api-check does (its dexdump reader, android.jar and the SDK's
// API levels). The plugin convention runs its rules after every plugin assemble, and :core:plugin and :core:ui their
// dumps. Never packaged into the app or a plugin.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":tools:api-check"))
    implementation(libs.asm)
    implementation(libs.kotlin.metadata.jvm)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
