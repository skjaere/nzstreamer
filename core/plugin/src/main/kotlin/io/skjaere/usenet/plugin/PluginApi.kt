package io.skjaere.usenet.plugin

/**
 * The plugin API's version, and the names of the meta-data a plugin's manifest carries, which its build writes (L584).
 * NZStreamer loads a plugin built against the same [MAJOR] as its own and a [MINOR] not above its own: a minor adds
 * members, never changes or removes one; a change or a removal raises the major (L614).
 */
object PluginApi {
    const val MAJOR = 1
    const val MINOR = 0

    /** "MAJOR.MINOR", as core/plugin/plugin-api.txt holds it and the plugin list's `api` gives it. */
    const val VERSION = "$MAJOR.$MINOR"

    /** The plugin's id, a string: its package is app.nzstreamer.plugin.<id>, its watch keys and secrets are kept under it. */
    const val META_ID = "nzs.plugin.id"

    /** The entry class's name, a string: an [NzPlugin] under the plugin's package, the only class the host makes. */
    const val META_ENTRY = "nzs.plugin.entry"

    /**
     * The API version the plugin was built against, as two integers: one "1.0" string would be stored as a number, which
     * reads as no string, and "1.10" as 1.1.
     */
    const val META_API_MAJOR = "nzs.plugin.api.major"
    const val META_API_MINOR = "nzs.plugin.api.minor"

    /**
     * The shared libraries the plugin was built against, "name=version" pairs separated by commas. The host refuses a
     * plugin that needs a newer one than its own, or one it doesn't know.
     */
    const val META_LIBS = "nzs.plugin.libs"
}

/**
 * The rules for the values a plugin hands the host, the same checks the host makes. A value outside its rule is refused
 * where it is handed over, with an [IllegalArgumentException] that names the rule and never the value: the value types
 * check theirs as they are made, the host's services and [PluginNav] theirs as they are called. Nothing is cut or changed
 * to fit.
 */
object PluginKeys {
    private val KEY = Regex("[A-Za-z0-9._:-]{1,200}")
    private val PAGE = Regex("[a-z0-9-]{1,40}")
    private val SECRET_NAME = Regex("[a-z0-9._-]{1,40}")

    /** An item's key ([PlayItem.key], [PluginWatch]): 1 to 200 letters, digits, dots, underscores, colons and dashes. */
    fun validKey(key: String): Boolean = KEY.matches(key)

    /** A page's name ([PluginNav.open]): 1 to 40 lower-case letters, digits and dashes. */
    fun validPage(page: String): Boolean = PAGE.matches(page)

    /** A page's argument ([PluginNav.open]): none, or at most 200 characters. */
    fun validArg(arg: String?): Boolean = arg == null || arg.characters() <= 200

    /** A secret's name ([PluginSecrets]): 1 to 40 lower-case letters, digits, dots, underscores and dashes. */
    fun validSecretName(name: String): Boolean = SECRET_NAME.matches(name)

    /** A possible match's reason ([Offered.reason]): 1 to 60 characters, not all blank. */
    fun validReason(reason: String): Boolean = reason.isNotBlank() && reason.characters() <= 60

    /** A tab's label ([PluginTab.label]): 1 to 16 characters, not all blank. */
    fun validLabel(label: String): Boolean = label.isNotBlank() && label.characters() <= 16

    // Characters as a reader counts them: a letter outside the BMP is one, not the two chars it takes.
    private fun String.characters(): Int = codePointCount(0, length)
}
