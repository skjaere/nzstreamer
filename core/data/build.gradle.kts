plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.skjaere.usenet.data"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.all { it.useJUnitPlatform() }
    // The test APK targets what the app targets: from Android 14 NsdManager picks its mDNS backend by the caller's
    // targetSdk, and the link's device tests (LinkPlatformDeviceTest) must meet the one the app meets.
    testOptions.targetSdk = 36
    // MigrationTestHelper reads the exported schemas.
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
}

kotlin { jvmToolchain(17) }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The schemas are androidTest assets: merge them after KSP has exported the current version.
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach { dependsOn("kspDebugKotlin") }

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(project(":core:releases"))
    // Play on TV's link: LinkIdentity hands out its LinkKey.
    api(project(":core:link"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.room.testing)
}
