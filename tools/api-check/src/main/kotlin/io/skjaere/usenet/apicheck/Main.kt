package io.skjaere.usenet.apicheck

import java.io.File
import kotlin.system.exitProcess

/**
 * Usage:
 *   --dexdump <build-tools/…/dexdump> --api-versions <platforms/android-N/data/api-versions.xml>
 *   --android-jar <platforms/android-N/android.jar> --min-sdk <n> --package <io.skjaere>…
 *   --dex <.dex, .apk or directory>…
 *   [--allowlist <file>] [--report <file>]
 *
 * Prints only the violations, never the dexdump listing, and exits with 1 when there are any.
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).groupBy({ it.first().removePrefix("--") }, { it.getOrNull(1) ?: usage() })
    fun option(name: String) = options[name]?.singleOrNull() ?: usage()

    val minSdk = option("min-sdk").toInt()
    val packages = options["package"] ?: usage()
    val prefixes = packages.map { it.replace('.', '/') + "/" }
    val dexFiles = options["dex"].orEmpty().flatMap { input ->
        File(input).walk().filter { it.isFile && it.extension in setOf("dex", "apk") }.sorted().toList()
    }
    if (dexFiles.isEmpty()) fail("no .dex or .apk files in ${options["dex"]}")

    val api = File(option("api-versions")).inputStream().buffered().use(ApiDatabase::parse)
    val allowlistPath = options["allowlist"]?.single()
    val allowlist = allowlistPath?.let { Allowlist.parse(File(it).readText()) } ?: Allowlist.EMPTY
    val scanned: (String) -> Boolean = { name -> prefixes.any { name.startsWith(it) } }
    val classes = dexdump(option("dexdump"), dexFiles, scanned)
    val scannedCount = classes.count { scanned(it.name) }
    if (scannedCount == 0) fail("no classes in ${packages.joinToString()} among the ${classes.size} in $dexFiles")

    val sdk = SdkStubs.open(File(option("android-jar")))
    val violations = MinApiChecker(api, minSdk, allowlist, sdk).check(classes)
    val scope = "${packages.joinToString { "$it.*" }} ($scannedCount classes)"
    val report = if (violations.isEmpty()) {
        "No uses of JDK APIs above minSdk $minSdk in $scope.\n"
    } else {
        val lines = violations.groupingBy { it }.eachCount().entries
            .sortedBy { (violation, _) -> violation.describe() }
            .map { (violation, count) -> "  ${violation.describe()}${if (count > 1) " ($count references)" else ""}" }
        buildString {
            appendLine("JDK APIs that minSdk $minSdk lacks, used ${violations.size} times in $scope:")
            lines.forEach(::appendLine)
            appendLine("D8 does not backport these: below the API level they throw NoSuchMethodError (NoClassDefFoundError, NoSuchFieldError).")
            appendLine("Replace them, or allowlist a use that is safe (e.g. behind an SDK_INT check) with the reason${allowlistPath?.let { " in $it" }.orEmpty()}.")
        }
    }
    options["report"]?.single()?.let { File(it).apply { parentFile?.mkdirs() }.writeText(report) }
    print(report)
    if (violations.isNotEmpty()) exitProcess(1)
}

/** Streams `dexdump -d -n` over [files] straight into the parser; the listing itself is never printed. */
private fun dexdump(dexdump: String, files: List<File>, scan: (String) -> Boolean): List<DexClass> {
    val process = ProcessBuilder(listOf(dexdump, "-d", "-n") + files.map { it.path })
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    val classes = process.inputStream.bufferedReader().useLines { DexDumpParser.parse(it, scan) }
    val exit = process.waitFor()
    if (exit != 0) fail("dexdump exited with $exit")
    return classes
}

private fun fail(message: String): Nothing {
    System.err.println("checkMinApi: $message")
    exitProcess(2)
}

private fun usage(): Nothing = fail(
    "usage: --dexdump <path> --api-versions <xml> --android-jar <jar> --min-sdk <n> --package <name>… --dex <file or dir>… " +
        "[--allowlist <file>] [--report <file>]",
)
