// The platform's example plugin (L623), NZStreamer's own test builds of a plugin, never released. The root build's plugin
// convention builds it as every plugin; this file adds what makes it a test fixture:
// - four builds of its debug build: v1 (versionCode 1), v2 (2), foreign (v2, signed with a throwaway key this build makes,
//   which nothing trusts), and newer (3, stamped with the next minor of the plugin API, so a host of this tree's API
//   refuses it whatever that version is), each listed for plugin-check's rules, which hold a real plugin to its sources;
// - its release build and its lint off;
// - the four APKs offered to the app's device tests (testPluginApks, which :app copies into their assets).
import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val pluginApiMinor: Int by rootProject.extra

// The foreign build's key: made with keytool the first time it is needed, under build/, never committed, printed or used
// for anything else. A throwaway, so its password is no secret.
val foreignStore: Provider<RegularFile> = layout.buildDirectory.file("foreign-key/foreign.p12")
val makeForeignKey by tasks.registering(Exec::class) {
    val store = foreignStore
    val keytool = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(17) }
        .map { it.metadata.installationPath.file("bin/keytool").asFile.path }
    outputs.file(store)
    args(
        "-genkeypair", "-noprompt", "-storetype", "PKCS12", "-keystore", store.get().asFile.path, "-storepass", "throwaway",
        "-alias", "foreign", "-keypass", "throwaway", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
        "-dname", "CN=Skeleton plugin's foreign test key",
    )
    doFirst {
        executable = keytool.get()
        store.get().asFile.apply { delete() }.parentFile.mkdirs()
    }
}

android {
    signingConfigs {
        create("foreign") {
            storeFile = foreignStore.get().asFile
            storePassword = "throwaway"
            keyAlias = "foreign"
            keyPassword = "throwaway"
        }
    }
    flavorDimensions += "build"
    productFlavors {
        create("v1") {
            dimension = "build"
            versionCode = 1
            versionName = "1"
        }
        create("v2") {
            dimension = "build"
            versionCode = 2
            versionName = "2"
        }
        create("foreign") {
            dimension = "build"
            versionCode = 2
            versionName = "2"
        }
        create("newer") {
            dimension = "build"
            versionCode = 3
            versionName = "3"
            manifestPlaceholders["nzsApiMinor"] = "${pluginApiMinor + 1}"
        }
    }
}

// What plugin-check's rules hold each build to (the root's convention reads it for the skeleton alone): its versionCode, and
// the plugin API minor it is stamped with, under the tree's major. A flavour above that sets another fails its rules.
extra["pluginCheckExpectations"] = mapOf(
    "v1" to (1 to pluginApiMinor),
    "v2" to (2 to pluginApiMinor),
    "foreign" to (2 to pluginApiMinor),
    "newer" to (3 to pluginApiMinor + 1),
)

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
    // A debug build is signed with the debug key whatever its flavour says: the foreign one gets its own key here.
    onVariants(selector().withFlavor("build" to "foreign")) { it.signingConfig.setConfig(android.signingConfigs.getByName("foreign")) }
}
tasks.named { it == "validateSigningForeignDebug" }.configureEach { dependsOn(makeForeignKey) }
tasks.named { it.startsWith("lint") }.configureEach { enabled = false }

// Each APK once plugin-check's rules passed on it (the convention's checkPluginRules<Variant>).
val testPluginApks: Configuration by configurations.creating {
    isCanBeResolved = false
}
androidComponents.onVariants { variant ->
    artifacts.add(testPluginApks.name, variant.artifacts.get(SingleArtifact.APK)) {
        builtBy("checkPluginRules${variant.name.replaceFirstChar { it.uppercase() }}")
    }
}
