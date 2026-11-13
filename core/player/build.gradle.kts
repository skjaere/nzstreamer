plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.skjaere.usenet.player"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The instrumented-test APK packages the same jars as the app; see app/build.gradle.kts.
    packaging {
        resources {
            excludes += listOf(
                "linux-x86-64/**", "linux-aarch64/**",
                "META-INF/INDEX.LIST", "META-INF/{AL2.0,LGPL2.1}", "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
            pickFirsts += listOf("COPYRIGHT.txt", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // JVM unit tests run against android.jar stubs: Media3's Util.normalizeLanguageCode builds its
    // replacement map with TextUtils.isEmpty, which returns false instead of throwing "not mocked".
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.all { it.useJUnitPlatform() }
    // The JVM tests read the device tests' media fixtures too (see the README there), and the app's chapters.mkv, a
    // file with chapters, which Media3 adds to its tracks' formats.
    sourceSets["test"].resources.srcDir("src/androidTest/assets")
    sourceSets["test"].resources.srcDir(rootProject.file("app/src/androidTest/fixtures"))
}

kotlin { jvmToolchain(17) }

// NoPlatformDiagnosticsTest reads the app's main sources as text (every ExoPlayer.Builder there, L17): they are its
// inputs as much as this module's classes are, so a change to them alone runs it again rather than reusing its result.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("app/src/main/kotlin")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("appMainSources")
}

dependencies {
    // core:usenet carries the plain JNA jar transitively; on Android the AAR from
    // native:rapidyenc provides JNA together with libjnidispatch.so.
    api(project(":core:usenet")) {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    api(project(":native:rapidyenc"))
    api(project(":core:releases"))
    api(libs.media3.exoplayer)
    api(libs.media3.datasource)
    api(libs.media3.common)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
