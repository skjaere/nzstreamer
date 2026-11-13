package io.skjaere.usenet.manifestcheck

import java.io.File
import kotlin.system.exitProcess

/**
 * Usage: [--check tv] --manifest <merged AndroidManifest.xml> [--report <file>], or
 *        --check cast-starts-late --manifest <merged AndroidManifest.xml>… [--report <file>]
 *
 * tv (checkTvManifest, the default): what TVs need of the release build's merged manifest ([TvManifestChecker]).
 * cast-starts-late (checkCastStartsLate): nothing in any merged manifest given starts Google's Cast software without the
 * app's gate ([CastStartChecker]). Prints the problems and exits with 1 when there are any.
 */
fun main(args: Array<String>) {
    exitProcess(run(args))
}

internal fun run(args: Array<String>): Int {
    val options = args.toList().chunked(2).groupBy({ it.first().removePrefix("--") }, { it.getOrNull(1) })
    if (options.values.any { null in it }) return usage("manifest-check", "every option takes a value")
    return when (options["check"]?.let { it.singleOrNull() ?: return usage("manifest-check", "one --check") } ?: "tv") {
        "tv" -> tv(options)
        "cast-starts-late" -> castStartsLate(options)
        else -> usage("manifest-check", "--check tv|cast-starts-late, not ${options["check"]}")
    }
}

private fun tv(options: Map<String, List<String?>>): Int {
    val manifest = options["manifest"]?.singleOrNull()?.let(::File)
        ?: return usage("checkTvManifest", "usage: --manifest <merged AndroidManifest.xml> [--report <file>]")
    if (!manifest.isFile) return usage("checkTvManifest", "no such manifest: $manifest")
    val problems = TvManifestChecker.check(manifest.readText())
    val report = buildString {
        if (problems.isEmpty()) {
            appendLine("${manifest.name}: ready for TVs (Leanback, touchscreen and faketouch not required, nothing else required, a TV launcher activity and a banner)")
        } else {
            appendLine("${manifest.name}: what a TV needs is missing:")
            problems.forEach { appendLine("  $it") }
            appendLine("Phones and TVs share one APK: app/src/main/AndroidManifest.xml declares what TVs need, and overrides a")
            appendLine("library's entry with tools:node=\"replace\" (a feature) or tools:node=\"remove\" (a permission).")
        }
    }
    return finish(options, report, problems.isEmpty())
}

/** Each manifest given (the release, debug and beta builds' merged ones), named by its path in the report. */
private fun castStartsLate(options: Map<String, List<String?>>): Int {
    val manifests = options["manifest"].orEmpty().map { File(it!!) }
    if (manifests.isEmpty()) return usage("checkCastStartsLate", "usage: --check cast-starts-late --manifest <merged AndroidManifest.xml>… [--report <file>]")
    manifests.filterNot { it.isFile }.takeIf { it.isNotEmpty() }?.let { return usage("checkCastStartsLate", "no such manifest: ${it.joinToString()}") }
    val problems = manifests.associateWith { CastStartChecker.check(it.readText()) }
    val report = buildString {
        problems.forEach { (manifest, found) ->
            if (found.isEmpty()) {
                appendLine(
                    "$manifest: Google's Cast software starts only where the app starts it (no options provider, MediaTransferReceiver, " +
                        "provider or initializer of Google's, MediaRouter's or Media3 Cast's, none of their components exported or with an intent " +
                        "filter, no expanded controller, no AD_ID; the app's cast service declared)",
                )
            } else {
                appendLine("$manifest: Google's Cast software could start, or a component of it, without the app's gate:")
                found.forEach { appendLine("  $it") }
            }
        }
        if (problems.values.any { it.isNotEmpty() }) {
            appendLine("Google's Cast software starts only through the app's gate (CastGate, L50, L107). A library's entry that starts it, or")
            appendLine("opens a component of it to others, is taken out with tools:node=\"remove\" in app/src/main/AndroidManifest.xml.")
        }
    }
    return finish(options, report, problems.values.all { it.isEmpty() })
}

/** Writes [report] where --report says and prints it: 0 when [passed], else 1. */
private fun finish(options: Map<String, List<String?>>, report: String, passed: Boolean): Int {
    options["report"]?.singleOrNull()?.let { File(it).apply { parentFile?.mkdirs() }.writeText(report) }
    print(report)
    return if (passed) 0 else 1
}

private fun usage(check: String, message: String): Int {
    System.err.println("$check: $message")
    return 2
}
