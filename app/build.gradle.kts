import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import org.gradle.api.configuration.BuildFeatures
import java.net.URI
import java.util.Properties
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional developer settings for debug and beta builds, read from the git-ignored local.properties:
//   devProvider.host, devProvider.port, devProvider.username, devProvider.password,
//   devProvider.tls (true/false), devProvider.connections
// Without devProvider.host, debug builds prefill the mock NNTP server instead.
// devTmdbKey: a TMDB key stored into the app's secret store on the first launch.
// devIndexer.N.url, devIndexer.N.apikey and an optional devIndexer.N.name (N = 1, 2, ...): indexers
// saved on a launch while the app has none.
// Debug builds alone (L182, W77): devTorBoxKey and devPremiumizeKey, a debrid service's API key each, and
// devTorrentIndexer.N.url, .apikey and an optional .name, torrent indexers as devIndexer.N: saved while the app has none,
// unless a device run turns that off (AppTestRunner, so a run never reaches the real accounts). Never in the bench build,
// a beta or release: checkNoDebridDevKeys looks for them there.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use { load(it) }
}
val devTmdbKey: String = localProperties.getProperty("devTmdbKey").orEmpty().trim()
val devProvider: Map<String, String> = localProperties.let { props ->
    props.stringPropertyNames()
        .filter { it.startsWith("devProvider.") }
        .associate { it.removePrefix("devProvider.") to props.getProperty(it).trim() }
}

/** [prefix].N.name, .url and .apikey, encoded for the app's DevIndexers: one line per N, its name, address and key by tabs. */
fun encodedIndexers(prefix: String): String = localProperties.let { props ->
    props.stringPropertyNames()
        .mapNotNull { Regex("""${Regex.escape(prefix)}\.(\d+)\.(name|url|apikey)""").matchEntire(it)?.destructured?.let { (n, field) -> Triple(n.toInt(), field, props.getProperty(it).trim()) } }
        .groupBy({ it.first }, { it.second to it.third })
        .toSortedMap()
        .values
        .joinToString("\n") { fields -> fields.toMap().let { listOf(it["name"].orEmpty(), it["url"].orEmpty(), it["apikey"].orEmpty()).joinToString("\t") } }
}
val devIndexers: String = encodedIndexers("devIndexer")
val devTorrentIndexers: String = encodedIndexers("devTorrentIndexer")
// The debrid dev settings' names (L182): the services' keys, and the torrent indexers' addresses and keys.
val DEBRID_SERVICE_KEY_NAMES = """devTorBoxKey|devPremiumizeKey"""
val DEBRID_TORRENT_INDEXER_NAMES = """devTorrentIndexer\.\d+\.(url|apikey)"""
val DEBRID_DEV_NAMES = "$DEBRID_SERVICE_KEY_NAMES|$DEBRID_TORRENT_INDEXER_NAMES"
// The dev settings a build made with devSettings() carries on purpose (a private build with preset settings, -PbetaDevSettings).
val CARRIED_DEV_NAMES = """devProvider\.(host|username|password)|devTmdbKey|devIndexer\.\d+\.(url|apikey)"""

/**
 * local.properties' settings whose names [names] matches, those set, each with its value, and an address's host as a
 * setting of its own: what a secret check looks for. For the checks alone: written to their build/tmp files, never shown.
 */
fun devCredentials(names: Regex): List<Pair<String, String>> = localProperties.let { props ->
    props.stringPropertyNames().sorted().filter { names.matches(it) }.flatMap { name ->
        val value = props.getProperty(name).trim()
        val host = if (name.endsWith(".url")) runCatching { URI(value).host }.getOrNull() else null
        listOf(name to value) + listOfNotNull(host?.let { "$name's host" to it })
    }.filter { it.second.isNotEmpty() }
}

/** The debrid dev keys, encoded for the app's DevServices: one line per service set, its name and key by a tab. */
val devServiceKeys: String = listOf("TorBox" to "devTorBoxKey", "Premiumize" to "devPremiumizeKey")
    .mapNotNull { (service, name) -> localProperties.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { "$service\t$it" } }
    .joinToString("\n")

fun String.asBuildConfigString() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t") + "\""

// -PbenchBuild=true: a `bench` build type, a release build signed with the debug key, so the instrumented tests can
// drive it, and they run against it. The stream bench (tools/stream-bench) measures it: the app as it ships, not as a
// debug build runs. A debuggable process runs with CheckJNI on every JNA call and a JIT that keeps code debuggable, and
// debug's native code (rapidyenc) is built without optimisation. It is also the build testers run on their TVs day to
// day, so it has Discover, as debug does (the source sets below): the release (Play) build plus Discover. Discover sits
// idle while a stream plays, so the bench's numbers stay the release build's. Without the property there is no bench
// build type and everything stays on debug.
val benchBuild = providers.gradleProperty("benchBuild").orNull.toBoolean()

// The app's version: version.txt, which release-please keeps, is the one place it is written. versionName is the version,
// and versionCode is ((MAJOR * 100 + MINOR) * 100 + PATCH) * 100 (VersionCodeTest): each release's code is above the one
// before, and the 99 codes above a release's are free for local betas. MINOR and PATCH stay under 100, and MAJOR under
// 2100 keeps every code within Play's limit.
val appVersion: String = providers.fileContents(rootProject.layout.projectDirectory.file("version.txt")).asText.orNull?.trim()
    ?: throw GradleException("version.txt is missing: it holds the app's version, MAJOR.MINOR.PATCH")
val baseVersionCode: Int = Regex("""(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)""").matchEntire(appVersion)
    ?.destructured?.let { (major, minor, patch) -> Triple(major.toInt(), minor.toInt(), patch.toInt()) }
    ?.takeIf { (major, _, _) -> major < 2100 }
    ?.let { (major, minor, patch) -> ((major * 100 + minor) * 100 + patch) * 100 }
    ?: throw GradleException(
        "version.txt holds '$appVersion': it must be MAJOR.MINOR.PATCH with MINOR and PATCH under 100 and MAJOR under 2100, " +
            "as versionCode is ((MAJOR * 100 + MINOR) * 100 + PATCH) * 100",
    )

// -PbetaBuild=N: a `beta` build type, beta N. A release build (with Discover, as bench has) that installs as its own app,
// "NZStreamer Beta" (app.nzstreamer.beta, beside io.skjaere.usenet, which debug, bench and a later Play release are), signed
// with the beta key (below). A local beta's N is one of the 99 codes above version.txt's (L29): versionCode N and
// versionName <version>-beta.N, so each beta updates the one before and the next release updates them all. N =
// version.txt's code itself, with -PbetaRelease=true, is the release's own APK, named as the version: only the release
// workflow and the checks of its build pass it, so one code always means one set of bytes.
// As built, a beta carries none of local.properties' dev settings: it is the one published at nzstreamer.app/beta (by
// the site's publish script, kept private), and checkBetaHasNoDevSettings runs after every assembleBeta to prove it.
// -PbetaDevSettings=true makes a private build with preset settings instead: it saves the dev provider, indexers and
// TMDB key on its first launch, as debug builds do, so they sit in the APK as plain text: for a few trusted testers,
// never published (and not checked). -PbetaConnections=N (with it) gives that build's provider N connections instead
// of devProvider.connections (they all share the account's limit). A later beta doesn't change settings an earlier one
// saved. Without -PbetaBuild there is no beta build type.
val betaRelease: Boolean = providers.gradleProperty("betaRelease").orNull?.let { value ->
    require(value == "true" || value == "false") { "-PbetaRelease takes true or false, not '$value'" }
    value == "true"
} ?: false
val betaBuild: Int? = providers.gradleProperty("betaBuild").orNull?.let { n ->
    val local = baseVersionCode + 1..baseVersionCode + 99
    requireNotNull(n.toIntOrNull()?.takeIf { it in local || it == baseVersionCode && betaRelease }) {
        "-PbetaBuild takes a local beta's number, ${local.first} to ${local.last}, not '$n'. version.txt's $appVersion is code " +
            "$baseVersionCode, which only its release's APK takes (with -PbetaRelease=true)."
    }
}
require(!betaRelease || betaBuild == baseVersionCode) {
    "-PbetaRelease=true builds the release's APK: it needs -PbetaBuild=$baseVersionCode, version.txt's $appVersion"
}
val betaDevSettings = providers.gradleProperty("betaDevSettings").orNull.toBoolean()
require(!(betaRelease && betaDevSettings)) { "-PbetaRelease=true builds the release's APK, which carries no dev settings: drop -PbetaDevSettings" }

// The hide flag (L192, L193): BuildConfig.HIDE_DEBRID, which the app reads through DebridGate alone. Hidden, a build offers no
// way into debrid (its Settings rows, First run's way, the setup page's cards, its searches), though its code is there and
// what a build that showed it saved stays saved. -PhideDebrid=true hides it in any build. A beta hides it unless built with
// -PwithDebrid=true, as debrid is hidden in betas by default (W68): a beta handed out, the published one or a private build
// typed from memory, offers no way in; the site's publish script passes it only when given --with-debrid. Debug, bench
// and release show it. -PexpectHideDebrid=true or false is HiddenBuildTest's expectation, given from outside and never
// derived from these: the app's unit tests get it as a system property. The two flags take true or false and nothing
// else: read as toBoolean() reads, a bare -PhideDebrid (an empty value) or =yes would be false and show debrid in a
// build asked to hide it.
fun debridFlag(name: String): Boolean {
    val value = providers.gradleProperty(name).orNull ?: return false
    require(value == "true" || value == "false") { "-P$name takes true or false, not '$value'" }
    return value == "true"
}
val hideDebrid = debridFlag("hideDebrid")
val withDebrid = debridFlag("withDebrid")
require(!withDebrid || betaBuild != null) { "-PwithDebrid=true is for a beta (-PbetaBuild=N), which hides debrid without it: every other build shows it" }
require(!(withDebrid && hideDebrid)) { "-PwithDebrid=true shows debrid and -PhideDebrid=true hides it: give one" }
val expectHideDebrid: String? = providers.gradleProperty("expectHideDebrid").orNull

// PrivacySitePageTest's page (L137, W6): -PprivacyPage=<file> names the site's privacy page, kept private with the site,
// whose publish script gives it before each deploy. The app's unit tests get it as the system property privacyPage;
// without it the class is skipped. A relative path is the root project's, and one that names no file fails the build.
val privacyPage: File? = providers.gradleProperty("privacyPage").orNull?.let { rootProject.file(it) }
require(privacyPage == null || privacyPage.isFile) { "-PprivacyPage names no file: $privacyPage" }

// Item 6(b) through the app's adapter (ServedStreamSpikeTest, W21): -PserveSpike=<the workstation's home-network address>
// with -PserveSpikeMedia=<a folder of synthetic files> serves them through ServedStream to the Shield's Cast receiver until
// the harness stops it, with its log shown; the options are core:serve's ServeSpikeTest's. The lead alone runs it, each
// casting session once the TV is free; without it, it is skipped.
val serveSpike: Map<String, String> =
    listOf("serveSpike", "serveSpikeMedia", "serveSpikePeer", "serveSpikePage", "serveSpikeMinutes", "serveSpikeLog")
        .mapNotNull { name -> providers.gradleProperty(name).orNull?.let { name to it } }.toMap()
val betaConnections: String? = providers.gradleProperty("betaConnections").orNull?.trim()
if (betaBuild != null) {
    require(!betaDevSettings || devProvider["host"].orEmpty().isNotBlank() && devIndexers.isNotBlank()) {
        "-PbetaDevSettings needs devProvider.* and devIndexer.N.* in local.properties: without them the beta would start with nothing set up"
    }
    require(betaConnections == null || betaDevSettings) { "-PbetaConnections is for a private build with preset settings: it needs -PbetaDevSettings=true" }
    require(betaConnections == null || betaConnections.toIntOrNull()?.takeIf { it > 0 } != null) {
        "-PbetaConnections takes a number of connections, not '$betaConnections'"
    }
}
// -PbetaScanExtra=<file> (L6): more strings checkBetaHasNoDevSettings looks for in the published beta, lines of
// label<TAB>value, such as a canary list kept outside the repo or the release workflow's secret. Without local.properties,
// as on a CI runner, the check has nothing else to look for. Its report counts them and never names one. A relative path
// is the root project's.
val betaScanExtra: File? = providers.gradleProperty("betaScanExtra").orNull?.let { rootProject.file(it) }
require(betaScanExtra == null || betaBuild != null && !betaDevSettings) {
    "-PbetaScanExtra is for the published beta's check: it needs -PbetaBuild, without -PbetaDevSettings"
}
require(betaScanExtra == null || betaScanExtra.isFile) {
    "-PbetaScanExtra names no file: give a file of label<TAB>value lines (a relative path is the root project's)"
}
// Every beta, published or private, installs as this app.
val betaApplicationId = "app.nzstreamer.beta"
// The beta key: every beta must be signed with it to update the one before, so it is backed up apart from this machine
// and never committed (L5). A beta is given it one of two ways, never both:
//   -PbetaSigning=<file>, a properties file with storeFile, storePassword, keyAlias and keyPassword, its storeFile
//   absolute, under the home folder when it starts with ~/, or else next to the file;
//   the four Gradle properties betaStoreFile (absolute), betaStorePassword, betaKeyAlias and betaKeyPassword, which the
//   release workflow gives as ORG_GRADLE_PROJECT_ environment variables, never on the command line.
// There is no default: a beta without the key fails. The errors name properties, never a path or a value. A beta never
// runs with the configuration cache, which would store the passwords in its entry.
class BetaKey(val store: File, val storePassword: String, val alias: String, val keyPassword: String)
// Gradle's own word on whether the configuration cache is asked for or on, which StartParameter's getter no longer gives.
abstract class BuildFeaturesHolder @Inject constructor(val features: BuildFeatures)
val betaKey: BetaKey? = if (betaBuild == null) null else {
    val configurationCache = objects.newInstance<BuildFeaturesHolder>().features.configurationCache
    require(!configurationCache.requested.getOrElse(false) && !configurationCache.active.get()) {
        "A beta never uses the configuration cache, which would store the beta key's passwords: build it with --no-configuration-cache"
    }
    val keyProperties = listOf("betaStoreFile", "betaStorePassword", "betaKeyAlias", "betaKeyPassword")
    val given: Map<String, String?> = keyProperties.associateWith { name -> providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() } }
    val signingFile: String? = providers.gradleProperty("betaSigning").orNull
    when {
        signingFile != null -> {
            require(given.values.all { it == null }) {
                "Give the beta key one way: -PbetaSigning, or betaStoreFile, betaStorePassword, betaKeyAlias and betaKeyPassword, not both"
            }
            val file = rootProject.file(signingFile)
            require(file.isFile) { "-PbetaSigning names no file: give the beta key's properties file (a relative path is the root project's)" }
            val properties = Properties().apply { file.reader().use { load(it) } }
            val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").filter { properties.getProperty(it).isNullOrBlank() }
            require(missing.isEmpty()) { "-PbetaSigning's file has no ${missing.joinToString(" and ")}" }
            val path = properties.getProperty("storeFile").trim()
            val store = if (path.startsWith("~/")) File(System.getProperty("user.home"), path.removePrefix("~/")) else file.parentFile.resolve(path)
            require(store.isFile) {
                "-PbetaSigning's storeFile names no file: give an absolute path, one starting with ~/ (the home folder), " +
                    "or one relative to the properties file"
            }
            BetaKey(store, properties.getProperty("storePassword"), properties.getProperty("keyAlias"), properties.getProperty("keyPassword"))
        }
        given.values.all { it == null } -> throw GradleException(
            "-PbetaBuild needs the beta key: set betaSigning, or betaStoreFile, betaStorePassword, betaKeyAlias and betaKeyPassword",
        )
        else -> {
            val missing = given.filterValues { it == null }.keys
            require(missing.isEmpty()) {
                "-PbetaBuild needs all four of betaStoreFile, betaStorePassword, betaKeyAlias and betaKeyPassword: " +
                    "${missing.joinToString(" and ")} ${if (missing.size == 1) "is" else "are"} missing"
            }
            val store = File(given.getValue("betaStoreFile")!!.trim())
            require(store.isAbsolute) { "betaStoreFile must be an absolute path" }
            require(store.isFile) { "betaStoreFile names no file: give the absolute path of the beta key's store" }
            BetaKey(store, given.getValue("betaStorePassword")!!, given.getValue("betaKeyAlias")!!, given.getValue("betaKeyPassword")!!)
        }
    }
}

android {
    namespace = "io.skjaere.usenet.app"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "io.skjaere.usenet"
        minSdk = 26
        targetSdk = 36
        versionCode = baseVersionCode
        versionName = appVersion
        // AndroidJUnitRunner, with the device tests' own defaults set before any test (src/androidTest's AppTestRunner).
        testInstrumentationRunner = "io.skjaere.usenet.app.AppTestRunner"
        // armeabi-v7a: many Android TV devices run a 32-bit userspace on 64-bit CPUs. The yEnc wrapper
        // declares C size_t as its pointer-width SizeT, so its JNA calls hold on 32-bit ABIs too.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // English alone (L141): the app's own words are English, and the libraries' translations (AndroidX, Media3, Google
    // Play services' Cast software, which every build carries since C2) would only mix languages on screen and add to
    // every APK.
    androidResources { localeFilters += "en" }

    signingConfigs {
        betaKey?.let { key ->
            create("beta") {
                storeFile = key.store
                storePassword = key.storePassword
                keyAlias = key.alias
                keyPassword = key.keyPassword
            }
        }
    }

    buildTypes {
        // The dev settings from local.properties, saved while the app has none (UsenetApplication's seedDevSettings).
        fun com.android.build.api.dsl.ApplicationBuildType.devSettings() {
            buildConfigField("boolean", "SEED_DEV_SETTINGS", "true")
            listOf("host", "port", "username", "password", "tls", "connections").forEach { key ->
                buildConfigField("String", "DEV_PROVIDER_${key.uppercase()}", devProvider[key].orEmpty().asBuildConfigString())
            }
            buildConfigField("String", "DEV_TMDB_KEY", devTmdbKey.asBuildConfigString())
            buildConfigField("String", "DEV_INDEXERS", devIndexers.asBuildConfigString())
        }
        // The debrid dev settings (L182): debug's alone; every other build type has them empty.
        fun com.android.build.api.dsl.ApplicationBuildType.devDebridSettings() {
            buildConfigField("String", "DEV_SERVICE_KEYS", devServiceKeys.asBuildConfigString())
            buildConfigField("String", "DEV_TORRENT_INDEXERS", devTorrentIndexers.asBuildConfigString())
        }
        // Not a constant (a call, not a literal), so no class compiles the value in: each reads its own build's, whatever an
        // incremental compile kept from a build with the other answer (a beta built with -PwithDebrid=true, then without).
        fun com.android.build.api.dsl.ApplicationBuildType.debridHidden(hidden: Boolean) =
            buildConfigField("boolean", "HIDE_DEBRID", "Boolean.parseBoolean(\"$hidden\")")
        debug {
            devSettings()
            devDebridSettings()
            debridHidden(hideDebrid)
        }
        release {
            debridHidden(hideDebrid)
            buildConfigField("boolean", "SEED_DEV_SETTINGS", "false")
            listOf("host", "port", "username", "password", "tls", "connections").forEach { key ->
                buildConfigField("String", "DEV_PROVIDER_${key.uppercase()}", "\"\"")
            }
            buildConfigField("String", "DEV_TMDB_KEY", "\"\"")
            buildConfigField("String", "DEV_INDEXERS", "\"\"")
            // Bench and both betas start from release (initWith): none of them carries the debrid dev keys.
            buildConfigField("String", "DEV_SERVICE_KEYS", "\"\"")
            buildConfigField("String", "DEV_TORRENT_INDEXERS", "\"\"")
            isMinifyEnabled = false
        }
        if (benchBuild) {
            create("bench") {
                initWith(getByName("release"))
                signingConfig = signingConfigs.getByName("debug")
                // simpleperf can sample it as it can a debug build.
                isProfileable = true
                // The libraries, rapidyenc's native build among them, as release builds them.
                matchingFallbacks += "release"
            }
        }
        if (betaBuild != null) {
            create("beta") {
                initWith(getByName("release"))
                if (betaDevSettings) {
                    devSettings()
                    betaConnections?.let { buildConfigField("String", "DEV_PROVIDER_CONNECTIONS", it.asBuildConfigString()) }
                }
                // Debrid is hidden in betas by default (L193).
                debridHidden(hideDebrid || !withDebrid)
                // The release's own APK is named as the version; a local beta as <version>-beta.N.
                if (betaBuild != baseVersionCode) versionNameSuffix = "-beta.$betaBuild"
                signingConfig = signingConfigs.getByName("beta")
                matchingFallbacks += "release"
            }
        }
    }
    if (benchBuild) testBuildType = "bench"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Lint's TV checks fail the build: the TV launcher's activity and banner, Leanback and touch not
    // required, no permission implying hardware a TV lacks (Play's TV quality TV-LM, TV-LB, TV-MT).
    // The baseline holds the issues lint already found when they were turned on; only new ones fail.
    lint {
        error += listOf(
            "MissingLeanbackLauncher", "MissingLeanbackSupport", "MissingTvBanner",
            "ImpliedTouchscreenHardware", "PermissionImpliesUnsupportedHardware", "UnsupportedTvHardware",
        )
        baseline = file("lint-baseline.xml")
        lintConfig = file("lint.xml")
    }

    // JVM unit tests run against android.jar stubs: framework calls a test doesn't care about
    // (Uri.parse building an Intent) return null/0 instead of throwing "not mocked".
    testOptions.unitTests.isReturnDefaultValues = true
    testOptions.unitTests.all {
        it.useJUnitPlatform()
        // BackupRulesTest reads the backup rules and the manifest as files, PrivacyTextTest the privacy policy's text and
        // VersionCodeTest version.txt, which the unit tests' classes don't carry: a change to them alone runs the tests again
        // rather than reusing their result.
        it.inputs.dir("src/main/res/xml").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("xmlResources")
        it.inputs.file("src/main/AndroidManifest.xml").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("manifest")
        it.inputs.file("src/main/assets/privacy.txt").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("privacyText")
        it.inputs.file(rootProject.file("version.txt")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("versionFile")
        // CastStartSourcesTest reads the sources it bans Google's widgets in as text, comments and the device tests' included,
        // and every resource's XML (L108): a comment, a device test or a layout changed alone runs it again too.
        it.inputs.files(fileTree("src") { include("main/kotlin/**", "discover/kotlin/**", "androidTest/kotlin/**", "release/kotlin/**", "main/res/**/*.xml") })
            .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("castStartSources")
        // HiddenBuildTest's expectation (L192); with it the tests run every time, so its check is never an earlier run's.
        expectHideDebrid?.let { expected ->
            it.systemProperty("expectHideDebrid", expected)
            it.outputs.upToDateWhen { false }
            it.outputs.cacheIf { false }
        }
        // The site's page for PrivacySitePageTest, given as HiddenBuildTest's expectation is and never an input: the page
        // isn't the app's. With it the tests run every time, so the page's check is never an earlier run's.
        privacyPage?.let { page ->
            it.systemProperty("privacyPage", page.absolutePath)
            it.outputs.upToDateWhen { false }
            it.outputs.cacheIf { false }
        }
        if ("serveSpike" in serveSpike) {
            serveSpike.forEach { (name, value) -> it.systemProperty(name, value) }
            // A run by hand takes as long as it needs, so the root build's limits are off for it.
            it.systemProperty("junit.jupiter.execution.timeout.mode", "disabled")
            it.timeout.unset()
            it.outputs.upToDateWhen { false }
            it.outputs.cacheIf { false }
            it.testLogging {
                showStandardStreams = true
                events("passed", "skipped", "failed")
            }
        }
    }

    packaging {
        resources {
            // glibc builds of librapidyenc.so bundled in the wrapper jar, plus licence/index clashes.
            excludes += listOf(
                "linux-x86-64/**", "linux-aarch64/**",
                "META-INF/INDEX.LIST", "META-INF/{AL2.0,LGPL2.1}", "META-INF/io.netty.versions.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
            // Every resilience4j jar ships the same notice files.
            pickFirsts += listOf("COPYRIGHT.txt", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }

    // The smoke test streams the NZB that tools/mock-seed wrote, compared against the source clip.
    sourceSets["androidTest"].assets.srcDir(rootProject.layout.buildDirectory.dir("smoke"))
    // The stream bench's NZB, of the articles its disk server serves: copied there by whoever runs it (tools/stream-bench).
    sourceSets["androidTest"].assets.srcDir(rootProject.layout.buildDirectory.dir("stream-bench"))
    // The media the shared mock NNTP server was seeded with, which the gap tests post again on a server of their own (LocalNntp).
    sourceSets["androidTest"].assets.srcDir("src/androidTest/fixtures")

    // Discover (TMDB's lists: trending, popular, ...) is in the debug, bench and beta builds only, never in the release (Play)
    // build: its screens in src/discover, their JVM tests in src/testDiscover (src/test is release's too), its lists in
    // :core:discover. Main reaches it only through AppExtras, which release answers with none (src/release), and
    // checkNoDiscover fails the release build if any of it leaks in.
    sourceSets["debug"].kotlin.srcDir("src/discover/kotlin")
    sourceSets["testDebug"].kotlin.srcDir("src/testDiscover/kotlin")
    if (benchBuild) {
        sourceSets["bench"].kotlin.srcDir("src/discover/kotlin")
        sourceSets["testBench"].kotlin.srcDir("src/testDiscover/kotlin")
    }
    if (betaBuild != null) {
        sourceSets["beta"].kotlin.srcDir("src/discover/kotlin")
        sourceSets["testBeta"].kotlin.srcDir("src/testDiscover/kotlin")
    }
}

// Each beta updates the one before: versionCode is the beta's number. Its applicationId replaces defaultConfig's (a build
// type can only add a suffix to it).
val betaApk: DirectoryProperty = objects.directoryProperty()
val betaApkLoader: Property<BuiltArtifactsLoader> = objects.property(BuiltArtifactsLoader::class.java)
val betaManifest: RegularFileProperty = objects.fileProperty()
if (betaBuild != null) {
    androidComponents.onVariants(androidComponents.selector().withBuildType("beta")) { variant ->
        variant.applicationId.set(betaApplicationId)
        variant.outputs.forEach { it.versionCode.set(betaBuild) }
        betaApk.set(variant.artifacts.get(SingleArtifact.APK))
        betaApkLoader.set(variant.artifacts.getBuiltArtifactsLoader())
        betaManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:player"))
    implementation(project(":core:newznab"))
    implementation(project(":core:data"))
    implementation(project(":core:metadata"))
    implementation(project(":core:search"))
    implementation(project(":core:logging"))
    // Set up with your phone (TV-8): in the release dex from the start, so checkMinApi checks its JDK calls.
    implementation(project(":core:handoff"))
    // Play on TV's link: in the release dex from the start too, so checkMinApi checks its JDK calls.
    implementation(project(":core:link"))
    // Cast's media server (C1): in every build, though it starts only when a cast does, so checkMinApi checks its JDK calls (L51).
    implementation(project(":core:serve"))
    // Debrid services' clients (D1): in every build (W67), in the release dex from the start, so checkMinApi checks their
    // JDK calls before the app calls them.
    implementation(project(":core:debrid"))
    // Discover's lists: the debug, bench and beta builds only (the source sets above).
    debugImplementation(project(":core:discover"))
    if (benchBuild) add("benchImplementation", project(":core:discover"))
    if (betaBuild != null) add("betaImplementation", project(":core:discover"))
    // Google Cast's libraries, in every build since C2 (L105): Media3's Cast player, which brings Google's Cast SDK and Play
    // services, Play services' base for its availability check, and MediaRouter, which the app calls itself. Only the app's
    // cast package uses them (CastExtras); checkMinApi reads it in the release dex with the rest of io.skjaere.
    implementation(libs.media3.cast)
    implementation(libs.play.services.cast.framework)
    implementation(libs.play.services.base)
    implementation(libs.mediarouter)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.media3.ui)
    // The player's media session (Bluetooth and headset keys, the Assistant, picture-in-picture), owned by PlayerActivity.
    implementation(libs.media3.session)
    implementation(libs.kotlinx.coroutines.android)
    // The phone setup's QR code; its reader decodes it in the tests.
    implementation(libs.zxing.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.datastore.core)
    // Search's view model test counts a real MultiIndexerSearch's API calls at its HTTP client.
    testImplementation(libs.ktor.client.mock)
    // Item 6(b)'s spike stack, for its run through ServedStream (ServedStreamSpikeTest).
    testImplementation(testFixtures(project(":core:serve")))
    testRuntimeOnly(libs.junit.platform.launcher)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // The collection's scale tests seed a library database of their own.
    androidTestImplementation(libs.room.runtime)
    androidTestImplementation(libs.room.ktx)
    // The phone setup's screen tests save into settings of their own, in memory.
    androidTestImplementation(libs.datastore.core)
}

// checkMinApi: the usenet libraries are compiled for JVM 17 so D8 can dex them, but nothing
// limits them to the JDK APIs Android has, and lint's NewApi does not read included builds'
// bytecode. This scans the release dex (what ships, after D8's backports; unlike debug it holds
// no dev credentials) for io.skjaere code using JDK APIs (java.*, javax.*, jdk.*, sun.*) newer
// than minSdk or missing from Android. android.* stays with lint, which understands SDK_INT
// guards. mergeDexRelease exists while release is unminified; without it the tool finds no dex
// and fails.
val apiCheckTool: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
}

dependencies {
    apiCheckTool(project(":tools:api-check"))
}

val checkMinApi by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when io.skjaere code in the release dex uses JDK APIs that minSdk devices lack."
    val releaseDex = files(tasks.named { it == "mergeDexRelease" })
    val sdkDir = androidComponents.sdkComponents.sdkDirectory
    val platform = sdkDir.map { it.dir("platforms/android-${android.compileSdk}") }
    val apiVersions = platform.map { it.file("data/api-versions.xml") }
    val androidJar = platform.map { it.file("android.jar") }
    val dexdump = sdkDir.map { it.file("build-tools/${android.buildToolsVersion}/dexdump") }
    val minSdk = android.defaultConfig.minSdk!!
    val allowlist = layout.projectDirectory.file("min-api-allowlist.txt")
    val report = layout.buildDirectory.file("reports/checkMinApi.txt")
    inputs.files(releaseDex).withPropertyName("releaseDex")
    inputs.file(apiVersions).withPropertyName("apiVersions")
    inputs.file(androidJar).withPropertyName("androidJar")
    inputs.file(allowlist).withPropertyName("allowlist")
    inputs.property("minSdk", minSdk)
    outputs.file(report)
    classpath = apiCheckTool
    mainClass = "io.skjaere.usenet.apicheck.MainKt"
    argumentProviders += object : CommandLineArgumentProvider {
        override fun asArguments() = listOf(
            "--dexdump", dexdump.get().asFile.path,
            "--api-versions", apiVersions.get().asFile.path,
            "--android-jar", androidJar.get().asFile.path,
            "--min-sdk", minSdk.toString(),
            "--package", "io.skjaere",
            "--allowlist", allowlist.asFile.path,
            "--report", report.get().asFile.path,
        ) + releaseDex.flatMap { listOf("--dex", it.path) }
    }
}

// checkNativeLibs: a device installs one lib/<abi>/ directory of the APK, so every ABI a phone or
// TV may run (32-bit ARM TV sticks included) needs every native library, and 64-bit ones need
// 16 KB LOAD alignment for 16 KB page devices. No library may keep its debug sections. Checks the
// release APK, which holds no dev credentials. The ABI list is fixed here on purpose: dropping
// one from abiFilters must fail.
val nativeCheckTool: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
}

dependencies {
    nativeCheckTool(project(":tools:native-check"))
}

val releaseApk: DirectoryProperty = objects.directoryProperty()
val releaseApkLoader: Property<BuiltArtifactsLoader> = objects.property(BuiltArtifactsLoader::class.java)
val releaseManifest: RegularFileProperty = objects.fileProperty()
val releaseBundle: RegularFileProperty = objects.fileProperty()
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    releaseApk.set(variant.artifacts.get(SingleArtifact.APK))
    releaseApkLoader.set(variant.artifacts.getBuiltArtifactsLoader())
    releaseManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
    releaseBundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
}
// The debug build's merged manifest: checkCastStartsLate reads it with the release build's (and a beta's).
val debugManifest: RegularFileProperty = objects.fileProperty()
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    debugManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
}

val nativeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val nativeLibs = listOf("librapidyenc.so", "libjnidispatch.so")

val checkNativeLibs by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when the release APK lacks a native library for a supported ABI, ships one with debug sections, or a 64-bit one is not 16 KB aligned."
    // Locals: the argument provider must not capture the script (configuration cache).
    val requiredAbis = nativeAbis
    val requiredLibs = nativeLibs
    val apkDir = releaseApk
    val apkLoader = releaseApkLoader
    val report = layout.buildDirectory.file("reports/checkNativeLibs.txt")
    inputs.dir(apkDir).withPropertyName("releaseApk")
    inputs.property("requiredAbis", requiredAbis)
    inputs.property("requiredLibs", requiredLibs)
    outputs.file(report)
    classpath = nativeCheckTool
    mainClass = "io.skjaere.usenet.nativecheck.MainKt"
    argumentProviders += object : CommandLineArgumentProvider {
        // Only the APKs the directory's metadata lists: a file an earlier build left there is not this build's.
        override fun asArguments(): List<String> {
            val apks = apkLoader.get().load(apkDir.get())?.elements?.map { it.outputFile }
                ?: error("No APK metadata in ${apkDir.get()}")
            return apks.flatMap { listOf("--apk", it) } + listOf("--report", report.get().asFile.path) +
                requiredAbis.flatMap { listOf("--abi", it) } + requiredLibs.flatMap { listOf("--lib", it) }
        }
    }
}

// checkTvManifest: one APK for phones and TVs. Lint's TV checks read the app's own manifest; this
// reads the merged release manifest, libraries' entries included, for what Play needs to offer the
// app to TVs and the TV launcher needs to list it: Leanback, touchscreen and faketouch declared not
// required, no other required feature (declared or implied by a permission), no portrait-only
// activity, an exported MAIN + LEANBACK_LAUNCHER activity, and a banner.
val manifestCheckTool: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
}

dependencies {
    manifestCheckTool(project(":tools:manifest-check"))
}

val checkTvManifest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when the merged release manifest keeps the app off TVs or out of the TV launcher."
    // A local: the argument provider must not capture the script (configuration cache).
    val manifest = releaseManifest
    val report = layout.buildDirectory.file("reports/checkTvManifest.txt")
    inputs.file(manifest).withPropertyName("releaseManifest")
    outputs.file(report)
    classpath = manifestCheckTool
    mainClass = "io.skjaere.usenet.manifestcheck.MainKt"
    argumentProviders += object : CommandLineArgumentProvider {
        override fun asArguments() = listOf("--manifest", manifest.get().asFile.path, "--report", report.get().asFile.path)
    }
}

// checkCastStartsLate (L107): every build carries Google's Cast libraries since C2, and Google's Cast software starts only
// where the app's gate starts it (CastGate, L50), as the privacy text says (W46). This reads the release and debug builds'
// merged manifests, and with -PbetaBuild the beta's (the published one's or a private one's), for what would start it
// without the gate, or open one of its components to the system or another app: the Cast framework's options provider,
// MediaRouter's MediaTransferReceiver, a provider or androidx.startup initializer of Google's, MediaRouter's or Media3
// Cast's, one of their components exported or with an intent filter, Google's expanded controller, and the advertising
// ID's permission (tools/manifest-check). Each manifest must declare the app's cast service and its permission, the
// check's control. In check, and run after every assembleRelease, bundleRelease and assembleBeta, as a library update
// can bring any of them.
val checkCastStartsLate by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when a merged manifest would start Google's Cast software, or open a component of it, without the app's gate."
    // Locals: the argument provider must not capture the script (configuration cache).
    val manifests = listOfNotNull(
        "releaseManifest" to releaseManifest,
        "debugManifest" to debugManifest,
        ("betaManifest" to betaManifest).takeIf { betaBuild != null },
    )
    val report = layout.buildDirectory.file("reports/checkCastStartsLate.txt")
    manifests.forEach { (name, manifest) -> inputs.file(manifest).withPropertyName(name) }
    outputs.file(report)
    classpath = manifestCheckTool
    mainClass = "io.skjaere.usenet.manifestcheck.MainKt"
    argumentProviders += object : CommandLineArgumentProvider {
        override fun asArguments() = listOf("--check", "cast-starts-late") +
            manifests.flatMap { (_, manifest) -> listOf("--manifest", manifest.get().asFile.path) } + listOf("--report", report.get().asFile.path)
    }
}
tasks.named { it == "assembleRelease" || it == "bundleRelease" || it == "assembleBeta" }.configureEach { finalizedBy(checkCastStartsLate) }

// checkBundleNativeLibs: Play takes an App Bundle (TV-G1, and Play's 32-bit, 64-bit and 16 KB rules,
// TV-G6) and cuts each device's APKs from it, so the bundle has to pass checkNativeLibs' checks too:
// bundleRelease's .aab, its base module's libraries. checkNativeLibs stays for sideloaded APKs.
val checkBundleNativeLibs by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when the release bundle lacks a native library for a supported ABI, ships one with debug sections, or a 64-bit one is not 16 KB aligned."
    // Locals: the argument provider must not capture the script (configuration cache).
    val requiredAbis = nativeAbis
    val requiredLibs = nativeLibs
    val bundle = releaseBundle
    val report = layout.buildDirectory.file("reports/checkBundleNativeLibs.txt")
    inputs.file(bundle).withPropertyName("releaseBundle")
    inputs.property("requiredAbis", requiredAbis)
    inputs.property("requiredLibs", requiredLibs)
    outputs.file(report)
    classpath = nativeCheckTool
    mainClass = "io.skjaere.usenet.nativecheck.MainKt"
    argumentProviders += object : CommandLineArgumentProvider {
        override fun asArguments() = listOf("--aab", bundle.get().asFile.path, "--report", report.get().asFile.path) +
            requiredAbis.flatMap { listOf("--abi", it) } + requiredLibs.flatMap { listOf("--lib", it) }
    }
}

// The debug build's dex, as a debuggable build merges it: the libraries', the modules' (:core:discover's among them) and
// the app's own (src/discover's among them).
val debugDexTasks = setOf("mergeExtDexDebug", "mergeLibDexDebug", "mergeProjectDexDebug")

// checkMinApiDiscover: Discover's code (debug, bench and beta only, the source sets above) never reaches the release dex that
// checkMinApi reads, so this reads the debug dex for Discover's packages alone, against the same minSdk and allowlist.
// It reports API references only, never strings, so debug's dev credentials stay unprinted. checkMinApi runs it.
val checkMinApiDiscover by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when Discover's code in the debug dex uses JDK APIs that minSdk devices lack."
    val debugDex = files(tasks.named { it in debugDexTasks })
    val sdkDir = androidComponents.sdkComponents.sdkDirectory
    val platform = sdkDir.map { it.dir("platforms/android-${android.compileSdk}") }
    val apiVersions = platform.map { it.file("data/api-versions.xml") }
    val androidJar = platform.map { it.file("android.jar") }
    val dexdump = sdkDir.map { it.file("build-tools/${android.buildToolsVersion}/dexdump") }
    val minSdk = android.defaultConfig.minSdk!!
    val allowlist = layout.projectDirectory.file("min-api-allowlist.txt")
    val report = layout.buildDirectory.file("reports/checkMinApiDiscover.txt")
    inputs.files(debugDex).withPropertyName("debugDex")
    inputs.file(apiVersions).withPropertyName("apiVersions")
    inputs.file(androidJar).withPropertyName("androidJar")
    inputs.file(allowlist).withPropertyName("allowlist")
    inputs.property("minSdk", minSdk)
    outputs.file(report)
    classpath = apiCheckTool
    mainClass = "io.skjaere.usenet.apicheck.MainKt"
    argumentProviders += object : CommandLineArgumentProvider {
        override fun asArguments() = listOf(
            "--dexdump", dexdump.get().asFile.path,
            "--api-versions", apiVersions.get().asFile.path,
            "--android-jar", androidJar.get().asFile.path,
            "--min-sdk", minSdk.toString(),
            "--package", "io.skjaere.usenet.discover",
            "--package", "io.skjaere.usenet.app.discover",
            "--allowlist", allowlist.asFile.path,
            "--report", report.get().asFile.path,
        ) + debugDex.flatMap { listOf("--dex", it.path) }
    }
}
checkMinApi { finalizedBy(checkMinApiDiscover) }

// checkNoDiscover: Discover (TMDB's lists) is in the debug, bench and beta builds only; the release (Play) build carries no
// Discover code, words, resources or tab. This reads the release dex, APK, bundle and merged manifest for Discover's
// packages, its canaries, TMDB's list paths and JustWatch's name, "Discover" in the app's own strings, and "discover" in a
// resource, an asset or the manifest (tools/feature-check). In the manifest, a library's class or key that an android:name
// gives doesn't count for the word (L106: Google's data transport, which Cast brings, declares TransportBackendDiscovery).
// The debug dex is its control: it must have every marker, so one renamed or dropped can't leave the check passing blind.
// It names files and entries, never what they hold (the debug dex has dev credentials). In check, and run after every
// assembleRelease and bundleRelease, so a Play build made without check fails all the same (its APK or bundle is written
// by then: don't upload one this failed).
val featureCheckTool: Configuration by configurations.creating {
    isCanBeConsumed = false
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
}

dependencies {
    featureCheckTool(project(":tools:feature-check"))
}

val checkNoDiscover by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when the release build carries anything of Discover, or when the debug build lacks what this looks for."
    // Locals: the argument provider must not capture the script (configuration cache).
    val releaseDex = files(tasks.named { it == "mergeDexRelease" })
    val debugDex = files(tasks.named { it in debugDexTasks })
    val dexdump = androidComponents.sdkComponents.sdkDirectory.map { it.file("build-tools/${android.buildToolsVersion}/dexdump") }
    val apkDir = releaseApk
    val apkLoader = releaseApkLoader
    val bundle = releaseBundle
    val manifest = releaseManifest
    val report = layout.buildDirectory.file("reports/checkNoDiscover.txt")
    inputs.files(releaseDex).withPropertyName("releaseDex")
    inputs.files(debugDex).withPropertyName("debugDex")
    inputs.dir(apkDir).withPropertyName("releaseApk")
    inputs.file(bundle).withPropertyName("releaseBundle")
    inputs.file(manifest).withPropertyName("releaseManifest")
    outputs.file(report)
    classpath = featureCheckTool
    mainClass = "io.skjaere.usenet.featurecheck.MainKt"
    argumentProviders += object : CommandLineArgumentProvider {
        // Only the APKs the directory's metadata lists: a file an earlier build left there is not this build's.
        override fun asArguments(): List<String> {
            val apks = apkLoader.get().load(apkDir.get())?.elements?.map { it.outputFile }
                ?: error("No APK metadata in ${apkDir.get()}")
            return listOf("--feature", "discover") + releaseDex.flatMap { listOf("--release-dex", it.path) } +
                apks.flatMap { listOf("--release-apk", it) } +
                listOf("--release-aab", bundle.get().asFile.path, "--release-manifest", manifest.get().asFile.path) +
                debugDex.flatMap { listOf("--control-dex", it.path) } +
                listOf("--dexdump", dexdump.get().asFile.path, "--report", report.get().asFile.path)
        }
    }
}
tasks.named { it == "assembleRelease" || it == "bundleRelease" }.configureEach { finalizedBy(checkNoDiscover) }

// checkBetaHasNoDevSettings: the beta built without -PbetaDevSettings is published for anyone (by the site's publish
// script, kept private), so it must carry none of local.properties' credentials: the provider's host, username and
// password, the TMDB key, each indexer's address (and its host) and API key. tools/feature-check looks for each in
// every entry of the APK and the file itself, as UTF-8 and UTF-16; its control is the applicationId, which a dex must
// hold. It names settings and entries, never a value, and looks for -PbetaScanExtra's strings too (above), counting
// them without a name. Run after every assembleBeta of that build; the publish script publishes only an APK it passed.
if (betaBuild != null && !betaDevSettings) {
    val checkBetaHasNoDevSettings by tasks.registering(JavaExec::class) {
        group = "verification"
        description = "Fails when the published beta carries any of local.properties' dev settings."
        // Locals: the argument provider must not capture the script (configuration cache).
        val apkDir = betaApk
        val apkLoader = betaApkLoader
        val control = betaApplicationId
        val extra = betaScanExtra
        // The debrid dev keys too (L182): the published beta carries none of them either.
        val settings: List<Pair<String, String>> = devCredentials(Regex("$CARRIED_DEV_NAMES|$DEBRID_DEV_NAMES"))
        // For this check only; build/ already holds the debug build's BuildConfig, which has the same values.
        val settingsFile = layout.buildDirectory.file("tmp/checkBetaHasNoDevSettings/settings.tsv")
        val report = layout.buildDirectory.file("reports/checkBetaHasNoDevSettings.txt")
        inputs.dir(apkDir).withPropertyName("betaApk")
        inputs.property("settings", settings.map { it.first })
        outputs.file(report)
        // A changed value leaves the APK and the names as they were: look again every time.
        outputs.upToDateWhen { false }
        classpath = featureCheckTool
        mainClass = "io.skjaere.usenet.featurecheck.SecretCheckKt"
        doFirst {
            settingsFile.get().asFile.apply { parentFile.mkdirs() }.writeText(settings.joinToString("") { "${it.first}\t${it.second}\n" })
        }
        argumentProviders += object : CommandLineArgumentProvider {
            // Only the APKs the directory's metadata lists: a file an earlier build left there is not this build's.
            override fun asArguments(): List<String> {
                val apks = apkLoader.get().load(apkDir.get())?.elements?.map { it.outputFile }
                    ?: error("No APK metadata in ${apkDir.get()}")
                return apks.flatMap { listOf("--apk", it) } +
                    listOf("--settings", settingsFile.get().asFile.path, "--control", control, "--report", report.get().asFile.path) +
                    extra?.let { listOf("--extra", it.path) }.orEmpty()
            }
        }
    }
    tasks.named { it == "assembleBeta" }.configureEach { finalizedBy(checkBetaHasNoDevSettings) }
}

// checkNoDebridDevKeys (L182): debug alone carries local.properties' debrid dev keys (devTorBoxKey, devPremiumizeKey and
// devTorrentIndexer.N.url and .apikey, with their hosts), so this looks for each of them in the release APK, the bench APK
// and both betas', after each of their assembles, as checkBetaHasNoDevSettings does (tools/feature-check's SecretCheck,
// under this task's name, a report each). A private build with preset settings (-PbetaDevSettings) carries its devIndexer
// and devProvider settings on purpose: a torrent indexer's value the same as one of them (one Prowlarr or Jackett has one
// host and one key for all its indexers) is reported as such and passes, while a service key never does. It names
// settings, never a value; with none set it has nothing to look for and passes.
fun checkNoDebridDevKeys(variant: String, apkDir: DirectoryProperty, apkLoader: Property<BuiltArtifactsLoader>, control: String, carriesDevSettings: Boolean) =
    tasks.register("checkNoDebridDevKeys${variant.replaceFirstChar { it.uppercase() }}", JavaExec::class) {
        group = "verification"
        description = "Fails when the $variant build carries any of local.properties' debrid dev keys."
        // Locals: the argument provider must not capture the script (configuration cache).
        val apks = apkDir
        val loader = apkLoader
        val check = name
        val strict = devCredentials(Regex(if (carriesDevSettings) DEBRID_SERVICE_KEY_NAMES else DEBRID_DEV_NAMES))
        val carriable = if (carriesDevSettings) devCredentials(Regex(DEBRID_TORRENT_INDEXER_NAMES)) else emptyList()
        val carried = if (carriesDevSettings) devCredentials(Regex(CARRIED_DEV_NAMES)) else emptyList()
        // For this check only, as checkBetaHasNoDevSettings' own.
        val dir = layout.buildDirectory.dir("tmp/$check")
        val report = layout.buildDirectory.file("reports/$check.txt")
        inputs.dir(apks).withPropertyName("apk")
        inputs.property("settings", (strict + carriable + carried).map { it.first })
        outputs.file(report)
        // A changed value leaves the APK and the names as they were: look again every time.
        outputs.upToDateWhen { false }
        classpath = featureCheckTool
        mainClass = "io.skjaere.usenet.featurecheck.SecretCheckKt"
        doFirst {
            val into = dir.get().asFile.apply { mkdirs() }
            mapOf("settings.tsv" to strict, "carriable.tsv" to carriable, "carried.tsv" to carried).forEach { (file, values) ->
                File(into, file).writeText(values.joinToString("") { "${it.first}\t${it.second}\n" })
            }
        }
        argumentProviders += object : CommandLineArgumentProvider {
            // Only the APKs the directory's metadata lists: a file an earlier build left there is not this build's.
            override fun asArguments(): List<String> {
                val files = loader.get().load(apks.get())?.elements?.map { it.outputFile } ?: error("No APK metadata in ${apks.get()}")
                val into = dir.get().asFile
                return files.flatMap { listOf("--apk", it) } + listOf(
                    "--check-name", check, "--settings", File(into, "settings.tsv").path, "--control", control,
                    "--advice", "Only debug builds may carry them (local.properties' devTorBoxKey, devPremiumizeKey and devTorrentIndexer.N).",
                    "--report", report.get().asFile.path,
                ) + if (carriesDevSettings) listOf("--settings-if-not-carried", File(into, "carriable.tsv").path, "--carried", File(into, "carried.tsv").path) else emptyList()
            }
        }
    }

val checkNoDebridDevKeysRelease = checkNoDebridDevKeys("release", releaseApk, releaseApkLoader, "io.skjaere.usenet", carriesDevSettings = false)
tasks.named { it == "assembleRelease" }.configureEach { finalizedBy(checkNoDebridDevKeysRelease) }
if (benchBuild) {
    val benchApk: DirectoryProperty = objects.directoryProperty()
    val benchApkLoader: Property<BuiltArtifactsLoader> = objects.property(BuiltArtifactsLoader::class.java)
    androidComponents.onVariants(androidComponents.selector().withBuildType("bench")) { variant ->
        benchApk.set(variant.artifacts.get(SingleArtifact.APK))
        benchApkLoader.set(variant.artifacts.getBuiltArtifactsLoader())
    }
    val checkNoDebridDevKeysBench = checkNoDebridDevKeys("bench", benchApk, benchApkLoader, "io.skjaere.usenet", carriesDevSettings = false)
    tasks.named { it == "assembleBench" }.configureEach { finalizedBy(checkNoDebridDevKeysBench) }
}
if (betaBuild != null) {
    // Both betas: the published one (no dev settings at all) and a private one with preset settings (its devSettings() carried on purpose).
    val checkNoDebridDevKeysBeta = checkNoDebridDevKeys("beta", betaApk, betaApkLoader, betaApplicationId, carriesDevSettings = betaDevSettings)
    tasks.named { it == "assembleBeta" }.configureEach { finalizedBy(checkNoDebridDevKeysBeta) }
}

tasks.named("check") { dependsOn(checkMinApi, checkMinApiDiscover, checkNativeLibs, checkTvManifest, checkBundleNativeLibs, checkNoDiscover, checkCastStartsLate) }
