// The UI kit (L612): NZStreamer's look (theme, type, colours, fonts) and the pieces its screens are built from, phone
// and TV (rows, fields, focus rings, the TV's chips, pills and menus). Moved out of the app so plugins draw with them
// too; its packages are the app's own (io.skjaere.usenet.app.ui, .tv, .play, .collection), so nothing that imports
// them changes. The namespace is only its R's, for the fonts.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.skjaere.usenet.ui"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // The kit's issues lint found while it was the app's, moved with it from the app's baseline; only new ones show.
    lint { baseline = file("lint-baseline.xml") }
    // JVM unit tests run against android.jar stubs: Configuration's and KeyEvent's constants are what they read.
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.all {
        it.useJUnitPlatform()
        // TypeScaleTest reads the app's main sources as text (no screen sets a size under 12 sp of its own): they are
        // its inputs as much as this module's classes are, so a change to them alone runs it again rather than reusing
        // its result.
        it.inputs.dir(rootProject.file("app/src/main/kotlin")).withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("appMainSources")
        // KitApiTest reads the kit's checked-in dump: a change to it alone runs it again.
        it.inputs.file("api/ui.api").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("kitApiDump")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // Compose's types are in the kit's own (Modifier, Color, TextStyle, the material components it wraps).
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    // BackHandler, for a TV field's Back (TvFieldGate).
    implementation(libs.activity.compose)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
