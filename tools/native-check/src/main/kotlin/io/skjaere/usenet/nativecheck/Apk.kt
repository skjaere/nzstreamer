package io.skjaere.usenet.nativecheck

import java.io.File
import java.util.zip.ZipFile

object Apk {
    private val NATIVE_LIB = Regex("""lib/([^/]+)/([^/]+\.so)""")

    /** The libraries under the APK's `lib/<abi>/`, the only place the installer takes them from. */
    fun nativeLibs(apk: File): List<NativeLib> = ZipFile(apk).use { zip ->
        zip.entries().asSequence()
            .mapNotNull { entry ->
                NATIVE_LIB.matchEntire(entry.name)?.destructured?.let { (abi, name) ->
                    NativeLib(abi, name, zip.getInputStream(entry).use { it.readBytes() })
                }
            }
            .toList()
    }
}
