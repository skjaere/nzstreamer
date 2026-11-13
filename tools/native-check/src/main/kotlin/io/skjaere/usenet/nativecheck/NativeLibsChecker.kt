package io.skjaere.usenet.nativecheck

/** A shared library an APK ships at `lib/<abi>/<name>`, or a bundle's module at `<module>/lib/<abi>/<name>` ([root] `<module>/`). */
class NativeLib(val abi: String, val name: String, val bytes: ByteArray, val root: String = "") {
    val path: String get() = "${root}lib/$abi/$name"
}

/**
 * Checks an APK's native libraries. A device installs one ABI directory and loads nothing from
 * the others, so every ABI in [requiredAbis], and every other ABI the APK carries, needs
 * [requiredLibs] plus each library any ABI ships: a missing ABI fails the install
 * (INSTALL_FAILED_NO_MATCHING_ABIS), a missing library fails at load time. Libraries for the
 * ABIs that have 16 KB page devices need LOAD segments aligned to at least 16 KB, or they do not
 * load there. No library may keep its debug sections: AGP strips them only when the NDK it
 * strips with is installed, and otherwise just warns and packages them.
 */
class NativeLibsChecker(private val requiredAbis: List<String>, private val requiredLibs: List<String>) {

    /** The problems of [libs], which an APK, or a bundle's module at [root] (`<module>/`), carries. */
    fun check(libs: List<NativeLib>, root: String = ""): List<String> = missing(libs, root) + libs.sortedBy { it.path }.flatMap(::problems)

    private fun missing(libs: List<NativeLib>, root: String): List<String> {
        val names = libs.groupBy({ it.abi }, { it.name })
        val expected = (requiredLibs + libs.map { it.name }).toSortedSet()
        return (requiredAbis + names.keys).distinct().sorted().mapNotNull { abi ->
            (expected - names[abi].orEmpty().toSet())
                .takeIf { it.isNotEmpty() }
                ?.let { "${root}lib/$abi/ lacks ${it.joinToString()}" }
        }
    }

    private fun problems(lib: NativeLib): List<String> = runCatching {
        listOfNotNull(misalignment(lib), debugSections(lib))
    }.getOrElse { listOf("${lib.path} ${it.message}") }

    private fun misalignment(lib: NativeLib): String? {
        if (lib.abi !in PAGE_16K_ABIS) return null
        val alignments = Elf.loadAlignments(lib.bytes)
        return when {
            alignments.isEmpty() -> "${lib.path} has no LOAD segments"
            alignments.any { it < PAGE_16K } ->
                "${lib.path} has LOAD segments aligned to ${alignments.min()} bytes; 16 KB page devices need $PAGE_16K"
            else -> null
        }
    }

    private fun debugSections(lib: NativeLib): String? = Elf.sectionNames(lib.bytes)
        .filter { it.startsWith(".debug_") }
        .takeIf { it.isNotEmpty() }
        ?.let { "${lib.path} is not stripped: ${it.joinToString()}" }

    companion object {
        const val PAGE_16K = 16 * 1024L

        /** The ABIs Android runs with 16 KB pages; 32-bit ones always use 4 KB. */
        val PAGE_16K_ABIS = setOf("arm64-v8a", "x86_64")
    }
}
