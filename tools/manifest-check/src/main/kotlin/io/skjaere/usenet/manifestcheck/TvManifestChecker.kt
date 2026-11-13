package io.skjaere.usenet.manifestcheck

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks a merged manifest for what TVs need of one APK for phones and TVs (Play's TV quality
 * TV-LM, TV-ML, TV-MT, TV-LB). Lint's TV checks read the app's own manifest; this reads the
 * merged one, so a library's `uses-feature` or permission counts too. Play offers the app to TVs
 * only when it declares Leanback, not as required (required would offer it to TVs only), and when
 * nothing it requires is hardware a TV may lack: a touchscreen and faketouch, which Play assumes
 * unless they are declared not required, any feature declared without `required="false"`, the
 * features some permissions imply, and the portrait screen a portrait-only activity implies. The
 * TV launcher lists only an exported activity with MAIN and LEANBACK_LAUNCHER, and shows a banner.
 */
object TvManifestChecker {
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private const val LEANBACK = "android.software.leanback"
    private val NOT_ON_TVS = listOf("android.hardware.touchscreen", "android.hardware.faketouch")
    private val PORTRAIT = setOf("portrait", "sensorPortrait", "reversePortrait", "userPortrait")
    private const val LOCATION_IMPLIED_UP_TO_SDK = 20

    /** Android's list of permissions that imply a required feature, unless that feature is declared. */
    private val IMPLIED: Map<String, List<String>> = buildMap {
        put("CAMERA", listOf("android.hardware.camera", "android.hardware.camera.autofocus"))
        put("RECORD_AUDIO", listOf("android.hardware.microphone"))
        listOf("BLUETOOTH", "BLUETOOTH_ADMIN").forEach { put(it, listOf("android.hardware.bluetooth")) }
        listOf("ACCESS_WIFI_STATE", "CHANGE_WIFI_STATE", "CHANGE_WIFI_MULTICAST_STATE").forEach { put(it, listOf("android.hardware.wifi")) }
        listOf("ACCESS_MOCK_LOCATION", "ACCESS_LOCATION_EXTRA_COMMANDS", "INSTALL_LOCATION_PROVIDER").forEach { put(it, listOf("android.hardware.location")) }
        listOf(
            "CALL_PHONE", "CALL_PRIVILEGED", "MODIFY_PHONE_STATE", "PROCESS_OUTGOING_CALLS", "READ_SMS", "RECEIVE_SMS",
            "RECEIVE_MMS", "RECEIVE_WAP_PUSH", "SEND_SMS", "WRITE_APN_SETTINGS", "WRITE_SMS",
        ).forEach { put(it, listOf("android.hardware.telephony")) }
    }.mapKeys { "android.permission.${it.key}" }

    /** Implied only for apps that target API 20 or lower. */
    private val IMPLIED_UP_TO_SDK_20 = mapOf(
        "android.permission.ACCESS_COARSE_LOCATION" to listOf("android.hardware.location", "android.hardware.location.network"),
        "android.permission.ACCESS_FINE_LOCATION" to listOf("android.hardware.location", "android.hardware.location.gps"),
    )

    fun check(manifest: String): List<String> {
        val root = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(InputSource(StringReader(manifest))).documentElement
        // Named features only: a glEsVersion entry is no hardware. Whether each is required: by any entry for it.
        val declared = root.children("uses-feature").filter { it.android("name").isNotEmpty() }
            .groupBy { it.android("name") }
            .mapValues { (_, entries) -> entries.any { it.android("required") != "false" } }
        val targetSdk = root.children("uses-sdk").firstOrNull()?.android("targetSdkVersion")?.toIntOrNull()
        val application = root.children("application").firstOrNull()
        val activities = application?.let { it.children("activity") + it.children("activity-alias") }.orEmpty()
        return buildList {
            when (declared[LEANBACK]) {
                null -> add("uses-feature $LEANBACK is not declared: Play doesn't offer the app to TVs")
                true -> add("uses-feature $LEANBACK is required: Play offers the app to TVs only")
                false -> Unit
            }
            NOT_ON_TVS.filter { it !in declared }.forEach {
                add("uses-feature $it is not declared required=\"false\": Play takes it as required, and TVs have none")
            }
            declared.filter { (name, required) -> required && name != LEANBACK }.keys.forEach {
                add("uses-feature $it is required: devices without it, TVs among them, can't install the app")
            }
            val implied = IMPLIED + IMPLIED_UP_TO_SDK_20.takeIf { (targetSdk ?: 1) <= LOCATION_IMPLIED_UP_TO_SDK }.orEmpty()
            (root.children("uses-permission") + root.children("uses-permission-sdk-23")).map { it.android("name") }.distinct().forEach { permission ->
                implied[permission].orEmpty().filter { it !in declared }.forEach {
                    add("uses-permission $permission implies uses-feature $it, required: declare it required=\"false\"")
                }
            }
            val launcher = activities.firstOrNull { it.android("exported") == "true" && it.isTvLauncher() }
            if (launcher == null) add("no exported activity has MAIN with LEANBACK_LAUNCHER: the TV launcher doesn't list the app")
            if (application?.android("banner").isNullOrEmpty() && launcher?.android("banner").isNullOrEmpty()) {
                val where = if (launcher != null) "the application or on ${launcher.android("name")}" else "the application"
                add("no android:banner on $where: the TV launcher has no banner to show")
            }
            activities.filter { it.android("screenOrientation") in PORTRAIT }.forEach {
                add("${it.android("name")} is portrait only (screenOrientation=\"${it.android("screenOrientation")}\"): Play takes it to need a portrait screen, which TVs lack")
            }
        }
    }

    private fun Element.isTvLauncher() = children("intent-filter").any { filter ->
        filter.children("action").any { it.android("name") == "android.intent.action.MAIN" } &&
            filter.children("category").any { it.android("name") == "android.intent.category.LEANBACK_LAUNCHER" }
    }

    private fun Element.android(attribute: String): String = getAttributeNS(ANDROID, attribute)

    /** The element's own children named [tag], not deeper ones. */
    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
}
