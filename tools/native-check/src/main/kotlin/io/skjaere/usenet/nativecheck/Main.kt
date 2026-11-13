package io.skjaere.usenet.nativecheck

import java.io.File
import kotlin.system.exitProcess

/**
 * Usage: (--apk <.apk file> | --aab <.aab file>)… --abi <required abi>… --lib <required library file name>… [--report <file>]
 *
 * Prints the problems and exits with 1 when there are any.
 */
fun main(args: Array<String>) {
    exitProcess(run(args))
}

/** What a device's libraries come from: an APK's lib/, or a bundle module's, which Play cuts the per-ABI APKs from. */
private class Package(val label: String, val root: String, val libs: List<NativeLib>, val checker: NativeLibsChecker)

internal fun run(args: Array<String>): Int {
    val options = args.toList().chunked(2).groupBy({ it.first().removePrefix("--") }, { it.getOrNull(1) })
    if (options.values.any { null in it }) return usage()
    val apks = options["apk"].orEmpty().map { File(it!!) }
    val aabs = options["aab"].orEmpty().map { File(it!!) }
    val abis = options["abi"]?.filterNotNull() ?: return usage()
    val libs = options["lib"]?.filterNotNull() ?: return usage()
    if (apks.isEmpty() && aabs.isEmpty()) return usage()
    (apks + aabs).filterNot { it.isFile }.takeIf { it.isNotEmpty() }?.let { return fail("no such file: ${it.joinToString()}") }

    val checker = NativeLibsChecker(abis, libs)
    // A bundle's base module carries the app's libraries; another module's own need only be on every ABI.
    val packages = apks.map { Package(it.name, "", Apk.nativeLibs(it), checker) } +
        aabs.flatMap { aab ->
            (mapOf("base" to emptyList<NativeLib>()) + Bundle.nativeLibs(aab)).map { (module, moduleLibs) ->
                Package("${aab.name} $module", "$module/", moduleLibs, if (module == "base") checker else NativeLibsChecker(abis, emptyList()))
            }
        }
    val results = packages.map { it to it.checker.check(it.libs, it.root) }
    val report = results.joinToString("") { (pkg, problems) ->
        buildString {
            if (problems.isEmpty()) {
                appendLine("${pkg.label}: every ABI carries every native library, stripped, 16 KB aligned on ${NativeLibsChecker.PAGE_16K_ABIS.sorted().joinToString()}:")
                pkg.libs.groupBy({ it.abi }, { it.name }).toSortedMap().forEach { (abi, names) ->
                    appendLine("  ${pkg.root}lib/$abi/: ${names.sorted().joinToString()}")
                }
            } else {
                appendLine("${pkg.label}: native library problems:")
                problems.forEach { appendLine("  $it") }
                appendLine("A device of a missing ABI cannot install the app, and a library missing from its ABI fails to load;")
                appendLine("ndk.abiFilters in app and native/rapidyenc decide which ABIs ship. Debug sections stay in when")
                appendLine("AGP cannot strip: ndkVersion (the ndk version in gradle/libs.versions.toml) must be an installed NDK.")
            }
        }
    }
    options["report"]?.single()?.let { File(it).apply { parentFile?.mkdirs() }.writeText(report) }
    print(report)
    return if (results.any { (_, problems) -> problems.isNotEmpty() }) 1 else 0
}

private fun fail(message: String): Int {
    System.err.println("checkNativeLibs: $message")
    return 2
}

private fun usage(): Int = fail("usage: (--apk <file> | --aab <file>)… --abi <abi>… --lib <file name>… [--report <file>]")
