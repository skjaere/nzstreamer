// The plugin API (L612): what NZStreamer offers a plugin, and all a plugin's code may name of NZStreamer's besides the UI
// kit. Plugins compile against it alone (compileOnly), so its classes always come from NZStreamer's own class loader. Its
// version is plugin-api.txt's (L614).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.skjaere.usenet.plugin"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.all {
        it.useJUnitPlatform()
        // PluginApiTest reads plugin-api.txt, which the tests' classes don't carry: a change to it alone runs the test again
        // rather than reusing its result.
        it.inputs.file("plugin-api.txt").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("pluginApiVersion")
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    // @Composable and ImageVector (Compose's runtime and ui), Flow and CoroutineScope: types the API's members take and give.
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
