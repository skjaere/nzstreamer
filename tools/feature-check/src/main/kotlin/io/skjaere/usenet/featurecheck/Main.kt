package io.skjaere.usenet.featurecheck

import java.io.File
import kotlin.system.exitProcess

/**
 * Usage: --feature discover --release-dex <.dex or directory>… [--release-apk <.apk>]… [--release-aab <.aab>]…
 *        [--release-manifest <file>]… --control-dex <.dex or directory>… --dexdump <build-tools/…/dexdump> [--report <file>]
 *
 * Fails (1) when the release build carries any of Discover (checkNoDiscover), or when the debug build's dex
 * ([--control-dex], the check's own control) lacks a marker it should have, so a renamed path or package can't leave the
 * check passing blind. Names the files and entries a marker is in, never what they hold: the debug build has dev
 * credentials.
 */
fun main(args: Array<String>) {
    exitProcess(run(args))
}

internal fun run(args: Array<String>, constStrings: (dexdump: String, files: List<File>) -> ConstStrings = ::dexdump): Int {
    val options = args.toList().chunked(2).groupBy({ it.first().removePrefix("--") }, { it.getOrNull(1) })
    if (options.values.any { null in it }) return usage()
    return when (options["feature"]?.singleOrNull()) {
        "discover" -> discover(options, constStrings)
        else -> usage()
    }
}

private fun Map<String, List<String?>>.files(name: String) = this[name].orEmpty().map { File(it!!) }

private fun discover(options: Map<String, List<String?>>, constStrings: (dexdump: String, files: List<File>) -> ConstStrings): Int {
    val check = "checkNoDiscover"
    val dexdump = options["dexdump"]?.singleOrNull() ?: return usage()
    val releaseDex = dexFiles(options.files("release-dex"))
    val controlDex = dexFiles(options.files("control-dex"))
    if (releaseDex.isEmpty()) return fail(check, "no release dex in ${options["release-dex"]}")
    if (controlDex.isEmpty()) return fail(check, "no debug dex in ${options["control-dex"]}")
    val packaged = options.files("release-apk") + options.files("release-aab") + options.files("release-manifest")
    packaged.filterNot { it.isFile }.takeIf { it.isNotEmpty() }?.let { return fail(check, "no such file: ${it.joinToString()}") }

    val hits = releaseDex.flatMap(ReleaseScan::dex) +
        options.files("release-apk").flatMap { ReleaseScan.zip(it, ReleaseScan.APK_RESOURCES) } +
        options.files("release-aab").flatMap { ReleaseScan.zip(it, ReleaseScan.BUNDLE_RESOURCES) } +
        options.files("release-manifest").flatMap(ReleaseScan::manifest)
    val strings = constStrings(dexdump, releaseDex)
    // A listing with none of the app's strings means the scan can't see them, not that there are none.
    if (strings.read == 0) return fail(check, "dexdump listed no const-strings in the app's own classes (${Discover.APP_CLASSES}) of $releaseDex")
    val named = listOfNotNull(Hit("the release dex", "${strings.named} const-string(s) with \"${Discover.NAME}\" in the app's own classes").takeIf { strings.named > 0 })
    val found = controlDex.flatMap { Discover.required.foundIn(it.readBytes()) }.toSet()
    val missing = Discover.required.filterNot { it in found }

    val looked = listOf("${releaseDex.size} dex files") + packaged.map { it.name }
    val report = buildString {
        if (hits.isEmpty() && named.isEmpty()) {
            appendLine("$check: the release build carries nothing of Discover.")
            appendLine("  Looked in ${looked.joinToString()} for Discover's classes and canary, TMDB's list paths, JustWatch's name, and \"${Discover.WORD}\" in a resource, an asset or the manifest (its libraries' names aside).")
            appendLine("  Read ${strings.read} const-strings in the app's own classes: none with \"${Discover.NAME}\".")
        } else {
            appendLine("$check: the release (Play) build carries Discover, which only the debug, bench and beta builds may:")
            (hits + named).forEach { appendLine("  ${it.where}: ${it.what}") }
            appendLine("Discover's code is in app/src/discover and :core:discover, which the release build doesn't compile; main reaches it")
            appendLine("only through AppExtras (src/release: none). Its words and TMDB's list paths belong there too.")
        }
        if (missing.isEmpty()) {
            appendLine("The debug build has every one of the ${Discover.required.size} markers the check looks for (its control).")
        } else {
            appendLine("$check: the debug build lacks markers the release check looks for, so it can't be trusted:")
            missing.forEach { appendLine("  ${it.describe()}") }
            appendLine("A package, a canary or a TMDB path was renamed or dropped: change tools/feature-check's markers with it.")
        }
    }
    return finish(options, report, hits.isEmpty() && named.isEmpty() && missing.isEmpty())
}

/** Writes [report] where --report says and prints it: 0 when [passed], else 1. */
private fun finish(options: Map<String, List<String?>>, report: String, passed: Boolean): Int {
    options["report"]?.singleOrNull()?.let { File(it).apply { parentFile?.mkdirs() }.writeText(report) }
    print(report)
    return if (passed) 0 else 1
}

/** The .dex files given, and those in the directories given. */
private fun dexFiles(inputs: List<File>): List<File> = inputs.flatMap { input ->
    input.walk().filter { it.isFile && it.extension == "dex" }.sorted().toList()
}

/** Streams `dexdump -d -n` over [files] into [ConstStringScan]; the listing itself is never printed. */
private fun dexdump(dexdump: String, files: List<File>): ConstStrings {
    val process = ProcessBuilder(listOf(dexdump, "-d", "-n") + files.map { it.path })
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    val strings = process.inputStream.bufferedReader().useLines { ConstStringScan.scan(it) }
    val exit = process.waitFor()
    if (exit != 0) throw IllegalStateException("dexdump exited with $exit")
    return strings
}

private fun fail(check: String, message: String): Int {
    System.err.println("$check: $message")
    return 2
}

private fun usage(): Int = fail(
    "feature-check",
    "usage: --feature discover --release-dex <file or dir>… [--release-apk <file>]… [--release-aab <file>]… [--release-manifest <file>]… " +
        "--control-dex <file or dir>… --dexdump <path> [--report <file>]",
)
