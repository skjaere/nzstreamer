package io.skjaere.usenet.featurecheck

import java.io.File
import java.util.zip.ZipFile

/** Where a marker is: a file, or an entry of one ("app-release.apk!classes2.dex"). Never what it holds. */
data class Hit(val where: String, val what: String)

/** Which of these markers [bytes] hold. Dex strings, class names and the markers are ASCII, so bytes compare as text. */
fun List<Marker>.foundIn(bytes: ByteArray): List<Marker> {
    val text = String(bytes, Charsets.ISO_8859_1)
    return filter { text.contains(it.text) }
}

/** Whether [bytes] hold [word] in any case. */
fun holdsWord(bytes: ByteArray, word: String): Boolean = String(bytes, Charsets.ISO_8859_1).contains(word, ignoreCase = true)

/** The release build, file by file. */
object ReleaseScan {
    /** An APK's resources and assets ("res/…", "assets/…"), its resource table. */
    val APK_RESOURCES = Regex("""(res|assets)/.*|resources\.arsc""")

    /** A bundle module's resources and assets ("base/res/…"), its resource table. */
    val BUNDLE_RESOURCES = Regex("""[^/]+/(res|assets)/.*|[^/]+/resources\.pb""")

    fun dex(file: File): List<Hit> = Discover.forbidden.foundIn(file.readBytes()).map { Hit(file.name, it.describe()) }

    /** Every entry of an APK or a bundle, its dex among them; a resource or an asset also by its name. */
    fun zip(file: File, resources: Regex): List<Hit> = ZipFile(file).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.flatMap { entry ->
            val where = "${file.name}!${entry.name}"
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val marked = Discover.forbidden.foundIn(bytes).map { Hit(where, it.describe()) }
            val resource = resources.matches(entry.name) &&
                (entry.name.contains(Discover.WORD, ignoreCase = true) || (entry.name.endsWith(".arsc") || entry.name.endsWith(".pb")) && holdsWord(bytes, Discover.WORD))
            marked + listOfNotNull(Hit(where, "\"${Discover.WORD}\" in a resource or an asset").takeIf { resource })
        }.toList()
    }

    /**
     * Discover's markers over the whole merged manifest, and [Discover.WORD] over all of it but a library's names
     * ([withoutLibraryNames]).
     */
    fun manifest(file: File): List<Hit> {
        val bytes = file.readBytes()
        val read = withoutLibraryNames(String(bytes, Charsets.ISO_8859_1))
        return Discover.forbidden.foundIn(bytes).map { Hit(file.name, it.describe()) } +
            listOfNotNull(Hit(file.name, "\"${Discover.WORD}\" in the manifest").takeIf { read.contains(Discover.WORD, ignoreCase = true) })
    }

    /** An android:name and its value, in either quote. */
    private val androidName = Regex("""android:name\s*=\s*("[^"]*"|'[^']*')""")

    /**
     * [manifest] without the values of the android:names that name a library's class or key: those starting with neither
     * the app's package (io.skjaere.) nor a dot (a class of the app's, as its own manifest writes it). Google's data
     * transport, which cast-framework brings to every build, declares `TransportBackendDiscovery` (L106). The app's own
     * names, every label, every other attribute and the text are still read.
     */
    internal fun withoutLibraryNames(manifest: String): String = androidName.replace(manifest) { match ->
        val name = match.groupValues[1].drop(1).dropLast(1)
        if (name.startsWith("io.skjaere.") || name.startsWith(".")) match.value else "android:name=\"\""
    }
}

/** How a report names a marker: what it is, and the text looked for. */
internal fun Marker.describe() = "$what (\"$text\")"

/** What a `dexdump -d` listing has of the app's own strings: how many const-strings its classes load, and how many with [Discover.NAME]. */
data class ConstStrings(val read: Int, val named: Int)

/** Reads a `dexdump -d` listing, keeping counts only: no string is kept or shown. */
object ConstStringScan {
    private val classDescriptor = Regex("""^\s+Class descriptor\s+: '(L[^']+;)'""")
    private val constString = Regex("""\|[0-9a-f]{4,}: const-string(?:/jumbo)? [vp]\d+, "(.*)" // string@[0-9a-f]+\s*$""")

    fun scan(lines: Sequence<String>, classes: String = Discover.APP_CLASSES, name: String = Discover.NAME): ConstStrings {
        var inApp = false
        var read = 0
        var named = 0
        lines.forEach { line ->
            if (line.startsWith("  Class descriptor")) {
                inApp = classDescriptor.find(line)?.groupValues?.get(1)?.startsWith(classes) == true
            } else if (inApp && "const-string" in line) {
                constString.find(line)?.let {
                    read++
                    if (name in it.groupValues[1]) named++
                }
            }
        }
        return ConstStrings(read, named)
    }
}
