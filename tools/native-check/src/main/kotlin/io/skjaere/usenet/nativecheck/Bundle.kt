package io.skjaere.usenet.nativecheck

import java.io.File
import java.util.zip.ZipFile

/** An App Bundle (.aab): Play cuts each device's APKs from it, an ABI's libraries from each module's `lib/<abi>/`. */
object Bundle {
    private val NATIVE_LIB = Regex("""([^/]+)/lib/([^/]+)/([^/]+\.so)""")

    /** Each module's libraries, by module name ("base" for the app's own); modules without any are left out. Reads those entries only. */
    fun nativeLibs(aab: File): Map<String, List<NativeLib>> = ZipFile(aab).use { zip ->
        zip.entries().asSequence()
            .mapNotNull { entry ->
                NATIVE_LIB.matchEntire(entry.name)?.destructured?.let { (module, abi, name) ->
                    NativeLib(abi, name, zip.getInputStream(entry).use { it.readBytes() }, root = "$module/")
                }
            }
            .groupBy { it.root.removeSuffix("/") }
    }
}
