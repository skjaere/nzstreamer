import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import java.time.Duration
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlinx.kover)
}

// The usenet libraries' releases pinned in gradle/libs.versions.toml win wherever a library's POM names another version
// of one of them, so no earlier tag (a Java 25 build) gets in. With -PusenetLibsDir a local checkout still replaces its
// library, whatever the version.
val usenetPins = mapOf(
    "nzb-streamer" to libs.versions.nzb.streamer,
    "ktor-nntp-client" to libs.versions.ktor.nntp.client,
    "kotlin-compression-utils" to libs.versions.kotlin.compression.utils,
    "rapidyenc-kotlin-wrapper" to libs.versions.rapidyenc.wrapper,
).mapValues { (_, version) -> version.get() }

subprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.github.skjaere") {
                usenetPins[requested.name]?.let { pin ->
                    useVersion(pin)
                    because("the usenet libraries' release pinned in gradle/libs.versions.toml")
                }
            }
        }
    }
}

// Each JVM test has 2 minutes, and so has each @BeforeAll, @BeforeEach, @AfterEach and @AfterAll. The slowest test takes
// about 15 s on CI. A test runs on a thread of its own: at the limit JUnit gives up on that thread and fails the test with
// the thread's stack, and the tests after it still run. So a hang names its test and the line its thread waits at. For a
// test in runBlocking, that is its runBlocking line: the stack doesn't show the call the coroutine is suspended in. The
// limit is off while a debugger is attached. A test task has 15 minutes, over ten times the slowest task's time: that
// limit catches a hang outside any test. A task stopped there writes no report, and the tests it was running end as
// skipped: skipped tests are logged, so the log names them. A failure's whole stack goes to the build's output, so CI's
// log shows it. The runs by hand that a -P property turns on (serveSpike, serveProbe, debridLive, linkPeer) take as long
// as they need: their modules turn both limits off for them.
subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("junit.jupiter.execution.timeout.default", "2m")
        systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
        timeout.set(Duration.ofMinutes(15))
        testLogging.exceptionFormat = TestExceptionFormat.FULL
        testLogging.events("failed", "skipped")
    }
}

// Coverage, with Kover as the usenet libraries have it. ./gradlew :koverXmlReportUnitTests writes one report,
// build/reports/kover/reportUnitTests.xml, of the JVM unit tests of the app and of every core and tools module: for an
// Android module, its debug build's. (Without the colon, each module writes a report of its own too.) CI sends it to
// Codecov. Kover's agent instruments classes inside a test's JVM, never a compiled class, so no APK or bundle changes.
// It runs only in the tests the report reads: the release, bench and beta unit tests (HiddenBuildTest among them) run
// without it.
kover {
    merge {
        subprojects { it.path == ":app" || it.path.startsWith(":core:") || it.path.startsWith(":tools:") }
        // The root project has no code: its variant gathers the others'.
        createVariant("unitTests") {
            val android = listOf("com.android.application", "com.android.library").any(project.pluginManager::hasPlugin)
            if (project != rootProject) add(if (android) "debug" else "jvm")
        }
        instrumentation {
            disabledForTestTasks.addAll("testReleaseUnitTest", "testBenchUnitTest", "testBetaUnitTest")
        }
    }
}
// Kover verifies each project's total coverage on check unless told not to. There are no rules to verify, so check
// writes no report and verifies nothing: it only finds the agent for the tests. The report is asked for by its name.
allprojects {
    pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
        extensions.configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
            reports { total { verify { onCheck = false } } }
        }
    }
}

// The plugin API's dumps (L625): what a plugin can call of :core:plugin and :core:ui, their public and protected
// signatures as Kotlin sees them (tools/plugin-check's ApiDump), checked in as core/plugin/api/plugin.api and
// core/ui/api/ui.api with plugin-api.txt's version as their first line. Read from each module's release class jar, as the
// app carries it. checkPluginApi (in check) fails on any difference, so no change to either reaches a plugin unnoticed;
// dumpPluginApi writes the dump again for a change made on purpose, under L614's version rules.
listOf(":core:plugin", ":core:ui").forEach { path ->
    project(path) {
        pluginManager.withPlugin("com.android.library") {
            val pluginCheckTool = configurations.create("pluginCheckTool") {
                isCanBeConsumed = false
                attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
            }
            dependencies { pluginCheckTool(project(":tools:plugin-check")) }
            // Locals: the argument providers must not capture the script (configuration cache).
            val releaseClasses = files(tasks.named { it == "bundleLibCompileToJarRelease" })
            val versionFile = rootProject.layout.projectDirectory.file("core/plugin/plugin-api.txt")
            val dumpFile = layout.projectDirectory.file("api/${project.name}.api")
            tasks.register<JavaExec>("dumpPluginApi") {
                group = "verification"
                description = "Writes the plugin API's dump of this module's release classes, for a change made on purpose (L614)."
                inputs.files(releaseClasses).withPropertyName("releaseClasses")
                inputs.file(versionFile).withPropertyName("pluginApiVersion")
                outputs.file(dumpFile)
                classpath = pluginCheckTool
                mainClass = "io.skjaere.usenet.plugincheck.MainKt"
                argumentProviders += object : CommandLineArgumentProvider {
                    override fun asArguments() = listOf("dump") + releaseClasses.flatMap { listOf("--classes", it.path) } +
                        listOf("--version-file", versionFile.asFile.path, "--out", dumpFile.asFile.path)
                }
            }
            val checkPluginApi = tasks.register<JavaExec>("checkPluginApi") {
                group = "verification"
                description = "Fails when this module's release classes differ from its checked-in plugin API dump."
                val report = layout.buildDirectory.file("reports/checkPluginApi.txt")
                inputs.files(releaseClasses).withPropertyName("releaseClasses")
                inputs.file(versionFile).withPropertyName("pluginApiVersion")
                // A missing dump is the tool's to name, with how to write it.
                inputs.files(dumpFile).withPropertyName("dump")
                outputs.file(report)
                classpath = pluginCheckTool
                mainClass = "io.skjaere.usenet.plugincheck.MainKt"
                argumentProviders += object : CommandLineArgumentProvider {
                    override fun asArguments() = listOf("check-dump") + releaseClasses.flatMap { listOf("--classes", it.path) } +
                        listOf("--version-file", versionFile.asFile.path, "--dump", dumpFile.asFile.path, "--report", report.get().asFile.path)
                }
            }
            tasks.named("check") { dependsOn(checkPluginApi) }
        }
    }
}

// The plugin API's version, as core/plugin/plugin-api.txt holds it (L614), and the shared libraries plugins take from the
// app at run time (L615), name=version in L615's order, from this catalog. A plugin's build stamps both in its manifest
// (the convention below), the app carries the libraries as BuildConfig.PLUGIN_HOST_LIBS, and the host compares the two
// before it loads a plugin (L584).
val pluginApi: List<Int> = providers.fileContents(layout.projectDirectory.file("core/plugin/plugin-api.txt")).asText.orNull?.trim()
    ?.let { Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)""").matchEntire(it) }?.groupValues?.drop(1)?.map { it.toInt() }
    ?: throw GradleException("core/plugin/plugin-api.txt must hold the plugin API's version, MAJOR.MINOR")
val pluginHostLibs: String = listOf<Pair<String, String?>>(
    // libs.versions.kotlin is also the group of kotlin-compression-utils' version: its own value is asProvider()'s.
    "kotlin-stdlib" to libs.versions.kotlin.asProvider().get(),
    "kotlinx-coroutines" to libs.versions.coroutines.get(),
    "kotlinx-serialization-json" to libs.kotlinx.serialization.json.get().version,
    "compose-bom" to libs.versions.compose.bom.get(),
    "lifecycle-viewmodel-compose" to libs.versions.lifecycle.get(),
    "activity-compose" to libs.versions.activity.compose.get(),
    "slf4j-api" to libs.versions.slf4j.get(),
).joinToString(",") { (name, version) -> "$name=${requireNotNull(version) { "the catalog names no version of $name" }}" }
extra["pluginApiMajor"] = pluginApi[0]
extra["pluginApiMinor"] = pluginApi[1]
extra["pluginHostLibs"] = pluginHostLibs

// Gradle's own word on whether the configuration cache is asked for or on, as the app's beta reads it.
abstract class PluginBuildFeatures @Inject constructor(val features: BuildFeatures)

// The plugin convention (L586, L623, L624): every :plugins:<id> project, an Android application whose own build file names
// its plugins and nothing else, is built here as NZStreamer loads it.
// - app.nzstreamer.plugin.<id>, minSdk 26, targetSdk and compileSdk 36, Java 17, its version from plugins/<id>/version.txt
//   (versionCode ((MAJOR * 100 + MINOR) * 100 + PATCH) * 100, as the app's).
// - Its manifest's meta-data from placeholders: nzsPluginId, nzsApiMajor and nzsApiMinor (plugin-api.txt's two numbers,
//   stored as integers, L584) and nzsLibs (the shared libraries above). The manifest names its entry class itself.
// - L615's libraries, the plugin API and the UI kit compileOnly: they come from the app's class loader, so the APK carries
//   the plugin's classes alone. Kotlin's standard library, which the Kotlin plugin adds by itself, is kept out of the
//   APK's runtime classpath too (not the tests'). No stripping, and no R8 (it would rename what the host calls).
// - Its resources' package id 0x7e: plugins carry none (L580), and a stray one could never take an id of the app's.
// - Its release build signed with the beta key from the four beta* properties CI's release workflow gives as
//   ORG_GRADLE_PROJECT_ variables, and only where GITHUB_ACTIONS is true. A plugin release build asked for anywhere else,
//   or given -PbetaSigning, fails here, at configuration, naming no path: the beta key's copy on a workstation (W158) can
//   never sign a plugin by accident. Asked for means a task that packages a release (assembleRelease, bundleRelease,
//   packageRelease, installRelease, with a flavour or not) by the plugin's path, or by name alone, which Gradle runs in
//   every project that has it. Anything else (assemble, build) packages a plugin's release unsigned outside CI.
// - plugin-check's rules (L625) after every assemble, and for the debug builds in check, holding the APK to what the
//   sources say: version.txt's versionCode, plugin-api.txt's stamp and the libraries above, whatever the plugin's build
//   file or manifest set. The skeleton's builds are test fixtures with versions and a stamp of their own (L623), which
//   its build file lists for the rules as pluginCheckExpectations; no other plugin's is read.
subprojects {
    if (!path.startsWith(":plugins:")) return@subprojects
    val pluginId = name
    require(Regex("[a-z][a-z0-9]{0,30}").matches(pluginId) && pluginId != "true" && pluginId != "false") {
        "A plugin's folder under plugins/ is its id: a lower-case letter, then up to 30 lower-case letters and digits, " +
            "neither true nor false (L612)"
    }
    pluginManager.withPlugin("com.android.application") {
        val version: String = providers.fileContents(layout.projectDirectory.file("version.txt")).asText.orNull?.trim()
            ?: throw GradleException("plugins/$pluginId/version.txt is missing: it holds the plugin's version, MAJOR.MINOR.PATCH")
        val versionCode: Int = Regex("""(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)""").matchEntire(version)
            ?.destructured?.let { (major, minor, patch) -> ((major.toInt() * 100 + minor.toInt()) * 100 + patch.toInt()) * 100 }
            ?.takeIf { version.substringBefore('.').toInt() < 2100 }
            ?: throw GradleException(
                "plugins/$pluginId/version.txt holds '$version': it must be MAJOR.MINOR.PATCH with MINOR and PATCH under 100 " +
                    "and MAJOR under 2100",
            )
        val release = pluginReleaseKey(pluginId)

        extensions.configure<ApplicationExtension> {
            namespace = "app.nzstreamer.plugin.$pluginId"
            compileSdk = 36
            defaultConfig {
                applicationId = "app.nzstreamer.plugin.$pluginId"
                minSdk = 26
                targetSdk = 36
                this.versionCode = versionCode
                versionName = version
                manifestPlaceholders += mapOf(
                    "nzsPluginId" to pluginId,
                    "nzsApiMajor" to "${pluginApi[0]}",
                    "nzsApiMinor" to "${pluginApi[1]}",
                    "nzsLibs" to pluginHostLibs,
                )
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            buildFeatures { compose = true }
            androidResources { additionalParameters += listOf("--package-id", "0x7e", "--allow-reserved-package-id") }
            release?.let { key ->
                signingConfigs.create("pluginRelease") {
                    storeFile = key.store
                    storePassword = key.storePassword
                    keyAlias = key.alias
                    keyPassword = key.keyPassword
                }
            }
            buildTypes.getByName("release") {
                isMinifyEnabled = false
                if (release != null) signingConfig = signingConfigs.getByName("pluginRelease")
            }
        }
        // The Kotlin plugin applies after Android's, as each plugin's build file names them.
        pluginManager.withPlugin("org.jetbrains.kotlin.android") {
            extensions.configure<KotlinAndroidProjectExtension> { jvmToolchain(17) }
        }

        dependencies {
            "compileOnly"(project(":core:plugin"))
            "compileOnly"(project(":core:ui"))
            "compileOnly"(platform(libs.compose.bom))
            "compileOnly"(libs.compose.ui)
            "compileOnly"(libs.compose.material3)
            "compileOnly"(libs.compose.material.icons.core)
            "compileOnly"(libs.kotlinx.coroutines.core)
            "compileOnly"(libs.kotlinx.serialization.json)
            "compileOnly"(libs.lifecycle.viewmodel.compose)
            "compileOnly"(libs.activity.compose)
            "compileOnly"(libs.slf4j.api)
        }
        configurations.configureEach {
            val tests = name.endsWith("UnitTestRuntimeClasspath") || name.endsWith("AndroidTestRuntimeClasspath")
            if (name.endsWith("RuntimeClasspath") && !tests) exclude(group = "org.jetbrains.kotlin")
        }

        // plugin-check's rules on each build's APK: its dex, entries, manifest and resources (L625).
        val pluginCheckTool = configurations.create("pluginCheckTool") {
            isCanBeConsumed = false
            attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
        }
        dependencies { pluginCheckTool(project(":tools:plugin-check")) }
        val android = extensions.getByType<ApplicationExtension>()
        val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
        androidComponents.onVariants { variant ->
            val capitalized = variant.name.replaceFirstChar { it.uppercase() }
            // Locals: the argument provider must not capture the script (configuration cache).
            val apkDir = variant.artifacts.get(SingleArtifact.APK)
            val apkLoader = variant.artifacts.getBuiltArtifactsLoader()
            // What the APK must say, from the sources: a build file that changes its versionCode or its stamp fails here.
            val fixture = if (path == ":plugins:skeleton") {
                ((extra.properties["pluginCheckExpectations"] as? Map<*, *>)?.get(variant.flavorName) as? Pair<*, *>)
                    ?: throw GradleException("The skeleton's build ${variant.flavorName} has no pluginCheckExpectations entry")
            } else {
                null
            }
            val expectedCode: Int = fixture?.first as? Int ?: versionCode
            val apiMajor = "${pluginApi[0]}"
            val apiMinor = "${fixture?.second as? Int ?: pluginApi[1]}"
            // The plugin API's and the UI kit's class jars, as this build compiles against them: the classes a plugin may name.
            val compileClasspath = configurations.getByName("${variant.name}CompileClasspath")
            val classesJar = Attribute.of("artifactType", String::class.java)
            val sharedJars = compileClasspath.incoming.artifactView {
                attributes { attribute(classesJar, "android-classes-jar") }
                componentFilter { it is ProjectComponentIdentifier && it.projectPath in setOf(":core:plugin", ":core:ui") }
            }.files
            // L615's libraries, and what they bring, resolved as this build compiles but in a configuration of the
            // convention's alone: by class, what NZStreamer has of them. A library the plugin's own build file adds, even
            // under their packages (Compose's extended icons), is never among them.
            val hostLibraries = configurations.create("${variant.name}PluginHostLibraries") {
                isCanBeConsumed = false
                compileClasspath.attributes.keySet().forEach { key ->
                    @Suppress("UNCHECKED_CAST")
                    attributes.attribute(key as Attribute<Any>, compileClasspath.attributes.getAttribute(key)!!)
                }
            }
            listOf(
                dependencies.platform(libs.compose.bom), libs.compose.ui, libs.compose.material3, libs.compose.material.icons.core,
                libs.kotlinx.coroutines.core, libs.kotlinx.serialization.json, libs.lifecycle.viewmodel.compose, libs.activity.compose,
                libs.slf4j.api,
            ).forEach { dependencies.add(hostLibraries.name, it) }
            dependencies.add(hostLibraries.name, "org.jetbrains.kotlin:kotlin-stdlib:${libs.versions.kotlin.asProvider().get()}")
            val libraryJars = hostLibraries.incoming.artifactView { attributes { attribute(classesJar, "android-classes-jar") } }.files
            val sdkDir = androidComponents.sdkComponents.sdkDirectory
            val buildTools = sdkDir.map { it.dir("build-tools/${android.buildToolsVersion}") }
            val platform = sdkDir.map { it.dir("platforms/android-${android.compileSdk}") }
            val report = layout.buildDirectory.file("reports/checkPluginRules$capitalized.txt")
            val rules = tasks.register<JavaExec>("checkPluginRules$capitalized") {
                group = "verification"
                description = "Fails when the ${variant.name} APK breaks the rules every plugin keeps (plugin-check, L625)."
                inputs.dir(apkDir).withPropertyName("apk")
                inputs.files(sharedJars).withPropertyName("sharedJars")
                inputs.files(libraryJars).withPropertyName("libraryJars")
                inputs.file(platform.map { it.file("android.jar") }).withPropertyName("androidJar")
                inputs.file(platform.map { it.file("data/api-versions.xml") }).withPropertyName("apiVersions")
                inputs.property("id", pluginId)
                inputs.property("versionCode", expectedCode)
                inputs.property("apiMajor", apiMajor)
                inputs.property("apiMinor", apiMinor)
                inputs.property("libs", pluginHostLibs)
                outputs.file(report)
                classpath = pluginCheckTool
                mainClass = "io.skjaere.usenet.plugincheck.MainKt"
                argumentProviders += object : CommandLineArgumentProvider {
                    // Only the APK the folder's metadata lists: a file an earlier build left there is not this build's.
                    override fun asArguments(): List<String> {
                        val apk = apkLoader.load(apkDir.get())?.elements?.singleOrNull()?.outputFile
                            ?: error("No single APK in the metadata of ${apkDir.get()}")
                        return listOf(
                            "rules", "--id", pluginId, "--apk", apk,
                            "--version-code", "$expectedCode", "--api-major", apiMajor, "--api-minor", apiMinor, "--libs", pluginHostLibs,
                            "--aapt2", buildTools.get().file("aapt2").asFile.path, "--dexdump", buildTools.get().file("dexdump").asFile.path,
                            "--android-jar", platform.get().file("android.jar").asFile.path,
                            "--api-versions", platform.get().file("data/api-versions.xml").asFile.path,
                            "--report", report.get().asFile.path,
                        ) + (sharedJars + libraryJars).flatMap { listOf("--shared-jar", it.path) }
                    }
                }
            }
            tasks.named { it == "assemble$capitalized" }.configureEach { finalizedBy(rules) }
            if (variant.debuggable) tasks.named("check") { dependsOn(rules) }
        }
    }
}

class PluginReleaseKey(val store: File, val storePassword: String, val alias: String, val keyPassword: String)

/**
 * The beta key for [pluginId]'s release build, or null when this build asks for none (the convention above). Asked for,
 * it comes only from CI: GITHUB_ACTIONS true, no -PbetaSigning, the four beta* properties, and no configuration cache,
 * which would store the passwords in its entry. Else the build fails here, at configuration. The messages name
 * properties, never a path or a value.
 */
fun Project.pluginReleaseKey(pluginId: String): PluginReleaseKey? {
    val packaging = Regex("""(assemble|bundle|package|install|sign)[A-Za-z0-9]*Release[A-Za-z0-9]*""")
    val asked = gradle.startParameter.taskNames.any { request ->
        packaging.matches(request.substringAfterLast(':')) &&
            (':' !in request || request.substringBeforeLast(':').removePrefix(":") == path.removePrefix(":"))
    }
    if (!asked) return null
    require(providers.gradleProperty("betaSigning").orNull == null) {
        "A plugin's release build never takes -PbetaSigning: plugins are signed in CI's release workflow alone, with the " +
            "key from its environment (L624)"
    }
    require(providers.environmentVariable("GITHUB_ACTIONS").orNull == "true") {
        "A plugin's release build runs in CI's release workflow alone, where plugins are signed with the key from its " +
            "environment (L624). Build $pluginId's debug build here, and ask for the app's release by its path (:app:assembleRelease)"
    }
    val names = listOf("betaStoreFile", "betaStorePassword", "betaKeyAlias", "betaKeyPassword")
    val given = names.associateWith { name -> providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() } }
    val missing = given.filterValues { it == null }.keys
    require(missing.isEmpty()) {
        val listed = missing.toList().let { if (it.size == 1) it.single() else it.dropLast(1).joinToString(", ") + " and " + it.last() }
        "A plugin's release build needs the key's four properties from CI's environment: $listed " +
            "${if (missing.size == 1) "is" else "are"} missing"
    }
    val configurationCache = objects.newInstance<PluginBuildFeatures>().features.configurationCache
    require(!configurationCache.requested.getOrElse(false) && !configurationCache.active.get()) {
        "A plugin's release build never uses the configuration cache, which would store the key's passwords: build it with " +
            "--no-configuration-cache"
    }
    val store = File(given.getValue("betaStoreFile")!!.trim())
    require(store.isAbsolute) { "betaStoreFile must be an absolute path" }
    require(store.isFile) { "betaStoreFile names no file: give the absolute path of the key's store" }
    return PluginReleaseKey(
        store, given.getValue("betaStorePassword")!!, given.getValue("betaKeyAlias")!!, given.getValue("betaKeyPassword")!!,
    )
}
