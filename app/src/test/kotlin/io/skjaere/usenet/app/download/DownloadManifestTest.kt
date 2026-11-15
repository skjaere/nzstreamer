package io.skjaere.usenet.app.download

import org.junit.jupiter.api.Test
import org.w3c.dom.Comment
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The downloads in the app's own manifest (item 12, L334, L358, L375, B5 without the gate), read as a file, as
 * BackupRulesTest reads it: the five permissions they need, each with a comment saying why as the others have, and none of
 * those they were built not to need (no battery-optimisation exemption, no storage permission, no new hardware feature);
 * both job services bound by JobScheduler alone and not exported; the service a dataSync one and not exported; the boot
 * and update receiver disabled, switched on only while needed (L375), and not exported; the notifications' receiver not
 * exported. The merged manifest's TV and Cast checks are :app:check's (checkTvManifest, checkCastStartsLate).
 */
class DownloadManifestTest {
    private val manifest: Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml")).documentElement

    private val application: Element = manifest.elements("application").single()

    @Test
    fun `the five permissions the downloads need are declared, each with a comment saying why`() {
        val permissions = manifest.elements("uses-permission")
        val declared = permissions.map { it.android("name") }
        DOWNLOADS.forEach { name -> assertTrue(name in declared, "uses-permission $name is missing") }
        permissions.filter { it.android("name") in DOWNLOADS }.forEach { permission ->
            val why = permission.comment(sharedWith = DOWNLOADS) ?: fail("uses-permission ${permission.android("name")} has no comment saying why")
            assertTrue("ownload" in why, "the comment over ${permission.android("name")} doesn't say it is the downloads': $why")
        }
    }

    @Test
    fun `none of the permissions the downloads were built not to need, and no new hardware feature`() {
        val declared = manifest.elements("uses-permission").map { it.android("name") } + manifest.elements("uses-permission-sdk-23").map { it.android("name") }
        LEFT_OUT.forEach { name -> assertTrue(name !in declared, "uses-permission $name: the downloads were built without it") }
        // The four that let the one APK go to phones and TVs, none required: nothing of the downloads adds one.
        val features = manifest.elements("uses-feature").associate { it.android("name") to it.android("required") }
        assertEquals(
            mapOf(
                "android.software.leanback" to "false", "android.hardware.touchscreen" to "false", "android.hardware.faketouch" to "false",
                "android.hardware.wifi" to "false",
            ),
            features,
        )
    }

    @Test
    fun `both job services are bound by JobScheduler alone and not exported`() {
        listOf(".download.DownloadJobService", ".download.DownloadWatchdogService").forEach { name ->
            val service = component("service", name)
            assertEquals(BIND_JOB_SERVICE, service.android("permission"), "$name: only JobScheduler may bind it")
            assertEquals("false", service.android("exported"), "$name is exported")
            assertTrue(service.elements("intent-filter").isEmpty(), "$name has an intent filter")
        }
    }

    @Test
    fun `the downloads' service is a dataSync one and not exported`() {
        val service = component("service", ".download.DownloadService")
        assertEquals("dataSync", service.android("foregroundServiceType"))
        assertEquals("false", service.android("exported"), "the downloads' service is exported")
        assertTrue(service.elements("intent-filter").isEmpty(), "the downloads' service has an intent filter")
    }

    @Test
    fun `the boot and update receiver is disabled until needed and not exported, and the notifications' one isn't exported`() {
        val boot = component("receiver", ".download.DownloadBootReceiver")
        assertEquals("false", boot.android("enabled"), "on by default: every boot would wake the app (L375)")
        assertEquals("false", boot.android("exported"), "the boot receiver is exported")
        val actions = boot.elements("intent-filter").flatMap { it.elements("action") }.map { it.android("name") }.toSet()
        assertEquals(setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED"), actions)
        val actionReceiver = component("receiver", ".download.DownloadActionReceiver")
        assertEquals("false", actionReceiver.android("exported"), "the notifications' Pause and Cancel could come from another app")
        assertTrue(actionReceiver.elements("intent-filter").isEmpty(), "the notifications' receiver has an intent filter")
    }

    /** The application's one [tag] named [name]. */
    private fun component(tag: String, name: String): Element =
        application.elements(tag).singleOrNull { it.android("name") == name } ?: fail("no $tag $name in the manifest")

    /** This element's own children named [tag]. */
    private fun Element.elements(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.android(attribute: String): String = getAttributeNS(ANDROID_NS, attribute)

    /**
     * The comment just above this element, over the elements of [sharedWith] it goes with: the text of the nearest comment
     * before it with nothing but whitespace and those between; null with none.
     */
    private fun Element.comment(sharedWith: Set<String>): String? {
        var node: Node? = previousSibling
        while (node != null) {
            when {
                node is Comment -> return node.data
                node.nodeType == Node.TEXT_NODE && node.textContent.isBlank() -> Unit
                node is Element && node.tagName == "uses-permission" && node.android("name") in sharedWith -> Unit
                else -> return null
            }
            node = node.previousSibling
        }
        return null
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val BIND_JOB_SERVICE = "android.permission.BIND_JOB_SERVICE"

        /** The downloads' five: the job's, the persisted job's and the boot receiver's, the service's two, and the notifications'. */
        val DOWNLOADS = setOf(
            "android.permission.RUN_USER_INITIATED_JOBS", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_DATA_SYNC", "android.permission.POST_NOTIFICATIONS",
        )

        /**
         * What the downloads don't ask for: a battery-optimisation exemption (L361), any storage or media permission (they
         * live in the app's own folders, W109), and the other foreground service types and exact alarms.
         */
        val LEFT_OUT = setOf(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO", "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.ACCESS_MEDIA_LOCATION",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE", "android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING",
            "android.permission.SCHEDULE_EXACT_ALARM", "android.permission.USE_EXACT_ALARM",
        )
    }
}
