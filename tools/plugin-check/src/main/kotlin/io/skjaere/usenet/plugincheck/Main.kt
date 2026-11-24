package io.skjaere.usenet.plugincheck

import io.skjaere.usenet.apicheck.ApiDatabase
import io.skjaere.usenet.apicheck.DexClass
import io.skjaere.usenet.apicheck.DexDumpParser
import io.skjaere.usenet.apicheck.MinApiChecker
import io.skjaere.usenet.apicheck.SdkStubs
import java.io.File
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Usage, one mode a run:
 *   rules --id <id> --apk <plugin.apk> --version-code <n> --api-major <n> --api-minor <n> --libs <name=version,…> --shared-jar <jar>…
 *         --aapt2 <build-tools/…/aapt2> --dexdump <build-tools/…/dexdump> --android-jar <jar> --api-versions <xml> [--report <file>]
 *     A plugin APK's dex, entries, manifest and resources against every plugin's rules ([PluginDexRules],
 *     [PluginManifestRules]), its entry class against its dex, and its JDK calls against minSdk 26 as :app:checkMinApi
 *     checks the app's. The version code, stamp and libraries are what the build's sources say; the shared jars are the
 *     plugin API's, the UI kit's and the shared libraries' class jars ([SharedClasses]).
 *   link --id <id> --apk <plugin.apk> --host <dex, apk or folder>… --dexdump <path> --android-jar <jar> --api-versions <xml> [--report <file>]
 *     Whether everything the plugin names outside its package is in NZStreamer's build ([LinkCheck]).
 *   dump --classes <jar or folder>… --version-file <plugin-api.txt> --out <file>
 *     Writes the dump of the classes' plugin API ([ApiDump]).
 *   check-dump --classes <jar or folder>… --version-file <plugin-api.txt> --dump <file> [--report <file>]
 *     Fails when the classes' dump differs from the checked-in one.
 *
 * Prints what it found, never a dexdump listing or a manifest, and exits with 1 when a check fails, 2 on bad arguments.
 */
fun main(args: Array<String>) {
    val mode = args.firstOrNull() ?: usage()
    val options = args.drop(1).chunked(2).groupBy({ it.first().removePrefix("--") }, { it.getOrNull(1) ?: usage() })
    fun option(name: String) = options[name]?.singleOrNull() ?: usage()
    fun files(name: String) = options[name].orEmpty().map(::File)
    val report = options["report"]?.singleOrNull()?.let(::File)
    when (mode) {
        "rules" -> rules(
            id = option("id"), apk = File(option("apk")), versionCode = option("version-code").toInt(),
            apiMajor = option("api-major").toInt(), apiMinor = option("api-minor").toInt(), libs = option("libs"), sharedJars = files("shared-jar"),
            aapt2 = option("aapt2"), dexdump = option("dexdump"), androidJar = File(option("android-jar")),
            apiVersions = File(option("api-versions")), report = report,
        )
        "link" -> link(
            id = option("id"), apk = File(option("apk")), host = files("host"), dexdump = option("dexdump"),
            androidJar = File(option("android-jar")), apiVersions = File(option("api-versions")), report = report,
        )
        "dump" -> {
            val out = File(option("out"))
            out.parentFile?.mkdirs()
            out.writeText(ApiDump.dump(version(File(option("version-file"))), classes(files("classes"))))
            println("plugin-check: wrote ${out.name}")
        }
        "check-dump" -> {
            val dump = File(option("dump"))
            if (!dump.isFile) fail("no dump at ${dump.name}: write it with the project's dumpPluginApi task")
            val differences = ApiDump.differences(dump.readText(), ApiDump.dump(version(File(option("version-file"))), classes(files("classes"))))
            finish(
                "${dump.name} matches the classes.",
                differences,
                "The plugin API's dump ${dump.name} differs from the classes",
                DUMP_ADVICE,
                report,
            )
        }
        else -> usage()
    }
}

private const val MIN_SDK = 26

/**
 * What check-dump says to do about a difference: L614's rule, and the two additions that are a minor only as the API's
 * KDoc keeps them.
 */
internal const val DUMP_ADVICE = "A change made on purpose: write the dump again with the project's dumpPluginApi task, and keep L614's rules " +
    "(once a plugin is released, an addition raises plugin-api.txt's minor, a change or a removal its major). A value added to an " +
    "enum the host hands plugins is an addition only because their `when` keeps an else branch, as each enum's KDoc says; a " +
    "member added to an interface a plugin implements (NzPlugin, PluginFilePicker) must have a body, or it is a change."

private fun rules(
    id: String, apk: File, versionCode: Int, apiMajor: Int, apiMinor: Int, libs: String, sharedJars: List<File>,
    aapt2: String, dexdump: String, androidJar: File, apiVersions: File, report: File?,
) {
    if (!apk.isFile) fail("no APK at ${apk.name}")
    if (sharedJars.isEmpty()) fail("no --shared-jar: the plugin API's, the UI kit's and the shared libraries' class jars are needed")
    val classes = dexdump(dexdump, listOf(apk)) { true }
    val entries = ZipFile(apk).use { zip -> zip.entries().asSequence().map { it.name }.toList() }
    val dexRules = PluginDexRules(id, SharedClasses.fromJars(sharedJars))
    val xmltree = run(aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk.path)
    val manifest = PluginManifestRules(id, versionCode, apiMajor, apiMinor, libs).check(
        badging = run(aapt2, "dump", "badging", "--include-meta-data", apk.path),
        xmltree = xmltree,
        resources = run(aapt2, "dump", "resources", apk.path),
    )
    // The manifest rules say what is wrong with an entry that is missing or outside the package; here, whether it loads.
    val entry = PluginManifestRules.entry(xmltree)?.takeIf { it.startsWith("app.nzstreamer.plugin.$id.") }
    val dex = dexRules.check(classes, entries) + entry?.let { dexRules.entry(it, classes) }.orEmpty()
    // The JDK's calls Android only has above minSdk, in the plugin's own classes, as checkMinApi finds the app's.
    val own = "app/nzstreamer/plugin/$id/"
    val api = apiVersions.inputStream().buffered().use(ApiDatabase::parse)
    val minApi = MinApiChecker(api, MIN_SDK, sdk = SdkStubs.open(androidJar))
        .check(classes.filter { it.name.startsWith(own) })
        .map { "${it.describe()}, above minSdk $MIN_SDK" }.distinct().sorted()
    finish(
        "${apk.name} keeps every plugin's rules: app.nzstreamer.plugin.$id $versionCode, API $apiMajor.$apiMinor, " +
            "${classes.count { it.name.startsWith(own) }} classes of its own.",
        dex + manifest + minApi,
        "${apk.name} breaks the plugins' rules",
        "Plugins take the shared libraries, the plugin API and the UI kit compileOnly from NZStreamer, log through slf4j, " +
            "and carry no resource, native library or component (L586, L624, L625).",
        report,
    )
}

private fun link(id: String, apk: File, host: List<File>, dexdump: String, androidJar: File, apiVersions: File, report: File?) {
    if (!apk.isFile) fail("no APK at ${apk.name}")
    val hostFiles = host.flatMap { input -> input.walk().filter { it.isFile && it.extension in setOf("dex", "apk") }.sorted().toList() }
    if (hostFiles.isEmpty()) fail("no .dex or .apk among --host")
    val own = "app/nzstreamer/plugin/$id/"
    val api = apiVersions.inputStream().buffered().use(ApiDatabase::parse)
    val missing = LinkCheck(dexdump(dexdump, hostFiles) { false }, SdkStubs.open(androidJar), api, MIN_SDK)
        .check(id, dexdump(dexdump, listOf(apk)) { it.startsWith(own) })
    finish(
        "Everything ${apk.name} names outside app.nzstreamer.plugin.$id is in NZStreamer's build or at API $MIN_SDK.",
        missing,
        "${apk.name} names what NZStreamer's build lacks",
        "Each would fail with NoSuchMethodError, NoSuchFieldError or NoClassDefFoundError as the plugin runs: keep the host's " +
            "side, or raise the plugin API's major (L614).",
        report,
    )
}

private fun version(file: File): String {
    val version = file.readText().trim()
    if (!Regex("""\d+\.\d+""").matches(version)) fail("${file.name} holds '$version', not MAJOR.MINOR")
    return version
}

private fun classes(inputs: List<File>): List<ByteArray> {
    if (inputs.isEmpty() || inputs.any { !it.exists() }) fail("--classes names no class jar or folder that exists")
    return ApiDump.classFiles(inputs)
}

/** Prints [found] (all of it to [report], the first 50 to the output) under [title] with [advice], or [passed]; exits 1 on any. */
private fun finish(passed: String, found: List<String>, title: String, advice: String, report: File?) {
    val text = if (found.isEmpty()) "$passed\n" else buildString {
        appendLine("$title (${found.size}):")
        found.forEach { appendLine("  $it") }
        appendLine(advice)
    }
    report?.apply { parentFile?.mkdirs() }?.writeText(text)
    if (found.size <= SHOWN) {
        print(text)
    } else {
        println("$title (${found.size}, the first $SHOWN here${report?.let { ", all in ${it.name}" }.orEmpty()}):")
        found.take(SHOWN).forEach { println("  $it") }
        println(advice)
    }
    if (found.isNotEmpty()) exitProcess(1)
}

private const val SHOWN = 50

/** Streams `dexdump -d -n` over [files] into the parser; the listing itself is never printed. */
private fun dexdump(dexdump: String, files: List<File>, scan: (String) -> Boolean): List<DexClass> {
    val process = ProcessBuilder(listOf(dexdump, "-d", "-n") + files.map { it.path })
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    val classes = process.inputStream.bufferedReader().useLines { DexDumpParser.parse(it, scan) }
    val exit = process.waitFor()
    if (exit != 0) fail("dexdump exited with $exit")
    return classes
}

/** Runs a build tool and gives its output; its errors go to this one's. */
private fun run(vararg command: String): String {
    val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    if (exit != 0) fail("${File(command.first()).name} ${command[1]} ${command[2]} exited with $exit")
    return output
}

private fun fail(message: String): Nothing {
    System.err.println("plugin-check: $message")
    exitProcess(2)
}

private fun usage(): Nothing = fail(
    "usage: rules --id <id> --apk <apk> --version-code <n> --api-major <n> --api-minor <n> --libs <list> --shared-jar <jar>… --aapt2 <path> " +
        "--dexdump <path> --android-jar <jar> --api-versions <xml> [--report <file>] | link --id <id> --apk <apk> " +
        "--host <dex, apk or folder>… --dexdump <path> --android-jar <jar> --api-versions <xml> [--report <file>] | " +
        "dump --classes <jar or folder>… --version-file <file> --out <file> | " +
        "check-dump --classes <jar or folder>… --version-file <file> --dump <file> [--report <file>]",
)
