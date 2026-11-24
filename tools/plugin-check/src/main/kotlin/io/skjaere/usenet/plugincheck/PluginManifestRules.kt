package io.skjaere.usenet.plugincheck

/**
 * A plugin APK's manifest and resources, as build-tools' aapt2 dumps them, against what the host checks before it loads
 * a plugin (L583, L584) and what plugins never have (L580, L624):
 * - its package is app.nzstreamer.plugin.<id>, at the expected versionCode, with minSdk 26;
 * - no activity, service, receiver or provider, and no permission: a plugin is code NZStreamer loads, never an app;
 * - the five meta-data, once each and stored as the host reads them: the id and the entry as strings (an id "true" would
 *   be a boolean), the API stamp as two integers at the expected version, the libraries as a string, this build's
 *   [libs] exactly (a list written by hand, or left from another build, would have the host compare the wrong versions);
 * - no resource.
 *
 * The stored types come from two dumps. `dump xmltree` shows a string quoted, a boolean as true or false and a number
 * bare, but it prints a float whose value is whole as an integer ("1.0" as 1). `dump badging --include-meta-data` prints
 * an integer's value and a float's raw bits, so the two agree only on an integer. (A float 0.0 has the bits of 0: nothing
 * tells the two apart.)
 */
class PluginManifestRules(
    private val id: String,
    private val versionCode: Int,
    private val apiMajor: Int,
    private val apiMinor: Int,
    private val libs: String,
) {
    private val packageName = "app.nzstreamer.plugin.$id"

    fun check(badging: String, xmltree: String, resources: String): List<String> {
        val manifest = XmlTree.parse(xmltree)
        val badged = BADGED_META_DATA.findAll(badging).associate { it.groupValues[1] to it.groupValues[2] }
        return (packageAndVersion(badging) + minSdk(manifest) + declared(manifest) + metaData(manifest, badged) + resources(resources)).sorted()
    }

    private fun packageAndVersion(badging: String): List<String> {
        val (name, code) = PACKAGE_LINE.find(badging)?.destructured ?: return listOf("aapt2's badging names no package")
        return listOfNotNull(
            "its package is $name, not $packageName".takeIf { name != packageName },
            "its versionCode is $code, not $versionCode".takeIf { code != versionCode.toString() },
        )
    }

    private fun minSdk(manifest: List<XmlTree.Element>): List<String> {
        val value = manifest.firstOrNull { it.name == "uses-sdk" }?.attributes?.get("minSdkVersion")
        if (value?.text == "$MIN_SDK" && value.stored == Stored.Integer) return emptyList()
        return listOf("its minSdk is ${value?.text ?: "not set"}, not $MIN_SDK")
    }

    private fun declared(manifest: List<XmlTree.Element>): List<String> = manifest.mapNotNull { element ->
        when (element.name) {
            in COMPONENTS -> "it declares a component (${element.name}): plugins declare none"
            in PERMISSIONS -> "it declares a permission (${element.name}): plugins declare none"
            else -> null
        }
    }.distinct()

    private fun metaData(manifest: List<XmlTree.Element>, badged: Map<String, String>): List<String> {
        val found = mutableListOf<String>()
        val byName = manifest.filter { it.name == "meta-data" && it.parent?.name == "application" }.groupBy { it.attributes["name"]?.text }

        // The meta-data's value as the host reads it, or null (and why) when it is missing, twice, or of another type.
        fun value(name: String, wanted: Stored): String? {
            val elements = byName[name].orEmpty()
            if (elements.size != 1) {
                found += if (elements.isEmpty()) "it has no $name meta-data" else "it has ${elements.size} $name meta-data, not one"
                return null
            }
            val value = elements.single().attributes["value"] ?: return null.also { found += "its $name has no value" }
            // An integer only where badging's raw data agrees; else a float printed whole.
            val stored = if (value.stored == Stored.Integer && badged[name] != value.text) Stored.Float else value.stored
            return if (stored == wanted) value.text else null.also { found += "its $name is stored as ${stored.words}, not ${wanted.words}" }
        }

        val id = value(ID, Stored.Text)
        val entry = value(ENTRY, Stored.Text)
        val major = value(API_MAJOR, Stored.Integer)
        val minor = value(API_MINOR, Stored.Integer)
        val libs = value(LIBS, Stored.Text)
        if (id != null && id != this.id) found += "its $ID is $id, not ${this.id}"
        if (entry != null && !(entry.startsWith("$packageName.") && CLASS_NAME.matches(entry))) {
            found += "its $ENTRY, $entry, isn't a class under $packageName"
        }
        if (major != null && minor != null && (major != "$apiMajor" || minor != "$apiMinor")) {
            found += "its API stamp is $major.$minor, not $apiMajor.$apiMinor"
        }
        if (libs != null && libs != this.libs) found += "its $LIBS is $libs, not this build's ${this.libs}"
        return found
    }

    private fun resources(resources: String): List<String> =
        RESOURCE_LINE.findAll(resources).map { "it carries a resource (${it.groupValues[1]}): plugins carry none" }.toList()

    /** How aapt2 stored a value, as its dumps print it. */
    internal enum class Stored(val words: String) {
        Text("a string"), Integer("an integer"), Float("a float"), Boolean("a boolean"), Reference("a reference"), Other("another type"),
    }

    /** The elements of `aapt2 dump xmltree`, in order, each with its parent and its attributes by their local names. */
    internal object XmlTree {
        class Value(val text: String, val stored: Stored)

        class Element(val name: String, val depth: Int, val parent: Element?) {
            val attributes = mutableMapOf<String, Value>()
        }

        // "  E: meta-data (line=6)"; "    A: http://schemas.android.com/apk/res/android:value(0x01010024)=1"
        private val ELEMENT = Regex("""^(\s*)E: (\S+)""")
        private val ATTRIBUTE = Regex("""^\s*A: (?:\S*:)?([A-Za-z]+)(?:\(0x[0-9a-f]+\))?=(.*)$""")
        private val STRING = Regex(""""(.*)" \(Raw: ".*"\)""")
        private val DECIMAL = Regex("""-?\d+""")
        private val HEX = Regex("""0x[0-9a-f]+""")
        private val FLOAT = Regex("""-?(\d+\.?\d*(e[+-]?\d+)?|inf|nan)""", RegexOption.IGNORE_CASE)

        fun parse(text: String): List<Element> {
            val elements = mutableListOf<Element>()
            val open = ArrayDeque<Element>()
            text.lineSequence().forEach { line ->
                val element = ELEMENT.find(line)
                if (element != null) {
                    // A child is indented deeper than its parent: close every element at this depth or deeper.
                    val depth = element.groupValues[1].length
                    while (open.lastOrNull()?.let { it.depth >= depth } == true) open.removeLast()
                    elements += Element(element.groupValues[2], depth, open.lastOrNull()).also(open::addLast)
                } else {
                    val attribute = ATTRIBUTE.find(line) ?: return@forEach
                    open.lastOrNull()?.attributes?.set(attribute.groupValues[1], value(attribute.groupValues[2]))
                }
            }
            return elements
        }

        private fun value(text: String): Value {
            STRING.matchEntire(text)?.let { return Value(it.groupValues[1], Stored.Text) }
            // An integer written in hex ("0x10") is stored as one too: kept by its decimal value, as badging prints it.
            if (HEX.matches(text)) return Value(Integer.parseUnsignedInt(text.removePrefix("0x"), 16).toString(), Stored.Integer)
            val stored = when {
                DECIMAL.matches(text) -> Stored.Integer
                text == "true" || text == "false" -> Stored.Boolean
                text.startsWith("@") -> Stored.Reference
                FLOAT.matches(text) -> Stored.Float
                else -> Stored.Other
            }
            return Value(text, stored)
        }
    }

    companion object {
        /** The entry class's name the manifest gives, as the host reads it (one string meta-data), or null where it gives none. */
        fun entry(xmltree: String): String? = XmlTree.parse(xmltree)
            .filter { it.name == "meta-data" && it.parent?.name == "application" && it.attributes["name"]?.text == ENTRY }
            .singleOrNull()?.attributes?.get("value")?.takeIf { it.stored == Stored.Text }?.text

        private const val MIN_SDK = 26
        private const val ID = "nzs.plugin.id"
        private const val ENTRY = "nzs.plugin.entry"
        private const val API_MAJOR = "nzs.plugin.api.major"
        private const val API_MINOR = "nzs.plugin.api.minor"
        private const val LIBS = "nzs.plugin.libs"
        private val COMPONENTS = setOf("activity", "activity-alias", "service", "receiver", "provider")
        private val PERMISSIONS = setOf(
            "uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m", "permission", "permission-group", "permission-tree",
        )
        private val PACKAGE_LINE = Regex("""^package: name='([^']*)' versionCode='([^']*)'""", RegexOption.MULTILINE)
        private val BADGED_META_DATA = Regex("""^meta-data: name='([^']*)' value='([^']*)'""", RegexOption.MULTILINE)
        private val RESOURCE_LINE = Regex("""^\s*resource 0x[0-9a-f]+ (\S+)""", RegexOption.MULTILINE)
        private val CLASS_NAME = Regex("""[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)+""")
    }
}
