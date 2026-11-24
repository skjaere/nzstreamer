package io.skjaere.usenet.app.plugins

/**
 * A plugin's id (L612): a lower-case letter, then up to 30 lower-case letters and digits, and neither "true" nor "false",
 * which aapt2 would store in the manifest as a boolean. Checked before an id goes into a package name, a path or a secret's
 * name.
 */
object PluginIds {
    private val ID = Regex("[a-z][a-z0-9]{0,30}")

    fun valid(id: String): Boolean = ID.matches(id) && id != "true" && id != "false"

    /** The plugin's package, and the prefix every one of its classes' names starts with. */
    fun packageOf(id: String): String = "app.nzstreamer.plugin.$id"
}
