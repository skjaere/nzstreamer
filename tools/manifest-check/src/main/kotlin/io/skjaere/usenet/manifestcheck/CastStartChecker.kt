package io.skjaere.usenet.manifestcheck

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks a merged manifest for entries that would start Google's Cast software, or let the system or another app start
 * one of its components, without the app's gate (L107): Google's software starts only where the app's CastGate starts
 * it (L50), which the privacy text says (W46). Every build carries Google's Cast libraries since C2, so their manifests
 * merge into every build's; today each of their components is unexported with no intent filter, and none adds a provider
 * or an initializer. A library update that changes that fails here:
 * - the Cast framework's options provider (a Cast button or a library asking for its context would start it);
 * - MediaRouter's MediaTransferReceiver (the system's output switcher would move media to a Cast device);
 * - a provider of Google's, MediaRouter's or Media3 Cast's, or an initializer of theirs under androidx.startup's provider
 *   (each runs as the app's process starts);
 * - one of their activities, services or receivers exported, or with an intent filter;
 * - Google's expanded controller at all; the advertising ID's permission.
 * Its control: the app's cast service, of type mediaPlayback, and its permission. Without them the file isn't the app's
 * merged manifest, and the check would pass on what it never read.
 */
object CastStartChecker {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private val GOOGLES = listOf("com.google.", "androidx.mediarouter.", "androidx.media3.cast.")
    private const val OPTIONS_PROVIDER = "com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME"
    private const val MEDIA_TRANSFER_RECEIVER = "androidx.mediarouter.media.MediaTransferReceiver"
    private const val STARTUP = "androidx.startup.InitializationProvider"
    private const val EXPANDED_CONTROLLER = "com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity"
    private const val AD_ID = "com.google.android.gms.permission.AD_ID"
    private val CAST_SERVICE = setOf(".cast.CastService", "io.skjaere.usenet.app.cast.CastService")
    private const val MEDIA_PLAYBACK = "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"
    private const val NOT_THE_APPS = "not the app's merged manifest"

    fun check(manifest: String): List<String> {
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(InputSource(StringReader(manifest))).documentElement
        val application = root.children("application").firstOrNull()
        val components = application?.let { app -> listOf("activity", "activity-alias", "service", "receiver").flatMap { app.children(it) } }.orEmpty()
        val providers = application?.children("provider").orEmpty()
        val permissions = (root.children("uses-permission") + root.children("uses-permission-sdk-23")).map { it.android("name") }
        return buildList {
            if (components.none { it.tagName == "service" && it.android("name") in CAST_SERVICE && "mediaPlayback" in it.android("foregroundServiceType").split('|') }) {
                add("$NOT_THE_APPS: no .cast.CastService with foregroundServiceType mediaPlayback")
            }
            if (MEDIA_PLAYBACK !in permissions) add("$NOT_THE_APPS: no uses-permission $MEDIA_PLAYBACK")
            root.descendants("meta-data").filter { it.android("name") == OPTIONS_PROVIDER }.forEach { _ ->
                add("meta-data $OPTIONS_PROVIDER: Google's Cast software could start without the app's gate, as a Cast button draws or a library asks for its context")
            }
            components.filter { it.tagName == "receiver" && it.android("name") == MEDIA_TRANSFER_RECEIVER }.forEach { _ ->
                add("receiver $MEDIA_TRANSFER_RECEIVER: the system's output switcher could move the app's media to a Cast device without the app")
            }
            providers.filter { it.android("name").isGoogles() }.forEach {
                add("provider ${it.android("name")}: it runs as the app's process starts, before the app's gate")
            }
            providers.filter { it.android("name") == STARTUP }.flatMap { it.children("meta-data") }.filter { it.android("name").isGoogles() }.forEach {
                add("meta-data ${it.android("name")} under $STARTUP: it runs as the app's process starts, before the app's gate")
            }
            components.filter { it.android("name").isGoogles() }.forEach {
                val name = "${it.tagName} ${it.android("name")}"
                if (it.android("exported") == "true") add("$name is exported: another app could start it")
                if (it.children("intent-filter").isNotEmpty()) add("$name has an intent filter: the system or another app could start it")
            }
            components.filter { it.android("name") == EXPANDED_CONTROLLER }.forEach {
                add("${it.tagName} $EXPANDED_CONTROLLER: Google's expanded controller, which starts Google's Cast software as it opens")
            }
            if (AD_ID in permissions) add("uses-permission $AD_ID: the advertising ID, which the app never reads")
        }
    }

    private fun String.isGoogles() = GOOGLES.any { startsWith(it) }

    private fun Element.android(attribute: String): String = getAttributeNS(ANDROID, attribute)

    /** The element's own children named [tag], not deeper ones. */
    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    /** Every element named [tag] under this one, at any depth. */
    private fun Element.descendants(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
}
