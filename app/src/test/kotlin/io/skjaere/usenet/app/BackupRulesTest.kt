package io.skjaere.usenet.app

import io.skjaere.usenet.data.DataContainer
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The secrets can't be read off this device, so no backup or device transfer may carry them; nor Play on TV's file,
 * whose pins mean nothing without this device's key (L29). Each with its DataStore temp file. Nor the last crash's log,
 * which leaves the device only when shared from Settings › Logs, as the privacy page says (L139). Nor the debrid folder,
 * whose listings and records of adds are the device's own (L233).
 */
class BackupRulesTest {
    private val res = File("src/main/res")

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement

    private fun Element.children(tag: String): List<Element> =
        getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.excludes(path: String) = listOf(path, "$path.tmp").all { excluded ->
        children("exclude").any { it.getAttribute("domain") == "file" && it.getAttribute("path") == excluded }
    }

    private fun Element.excludesSecrets() = excludes(DataContainer.SECRETS_PATH)

    /** The folder [dir] left out whole, with whatever is in it ("logs/" or "logs" as Android reads it). */
    private fun Element.excludesFolder(dir: String) =
        children("exclude").any { it.getAttribute("domain") == "file" && it.getAttribute("path").trimEnd('/') == dir }

    private fun rules(attribute: String): Element {
        val application = parse(File("src/main/AndroidManifest.xml")).children("application").single()
        val ref = application.getAttributeNS(ANDROID_NS, attribute)
        assertTrue(ref.startsWith("@xml/"), "android:$attribute is not set")
        return parse(File(res, "xml/${ref.removePrefix("@xml/")}.xml"))
    }

    @Test
    fun `secrets are left out of cloud backups and device transfers on Android 12+`() {
        val rules = rules("dataExtractionRules")
        assertEquals("data-extraction-rules", rules.tagName)
        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertTrue(rules.children(section).single().excludesSecrets(), "$section doesn't exclude ${DataContainer.SECRETS_PATH}")
        }
    }

    @Test
    fun `secrets are left out of full backups up to Android 11`() {
        val rules = rules("fullBackupContent")
        assertEquals("full-backup-content", rules.tagName)
        assertTrue(rules.excludesSecrets())
    }

    @Test
    fun `the link's file is left out of cloud backups, device transfers and full backups`() {
        val extraction = rules("dataExtractionRules")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertTrue(extraction.children(section).single().excludes(DataContainer.LINK_PATH), "$section doesn't exclude ${DataContainer.LINK_PATH} and its .tmp")
        }
        assertTrue(rules("fullBackupContent").excludes(DataContainer.LINK_PATH), "full backups don't exclude ${DataContainer.LINK_PATH} and its .tmp")
    }

    /**
     * The last crash's log holds the in-app log before the crash (casts and plays among it) under the device's header: a
     * transfer to a new phone would carry it there, though the privacy page says logs leave the device only when shared
     * from Settings › Logs (L139). Its temp file is in the same folder.
     */
    @Test
    fun `the last crash's log is left out of cloud backups, device transfers and full backups`() {
        val extraction = rules("dataExtractionRules")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertTrue(extraction.children(section).single().excludesFolder(AppContainer.LOGS_DIR), "$section doesn't exclude ${AppContainer.LOGS_DIR}/")
        }
        assertTrue(rules("fullBackupContent").excludesFolder(AppContainer.LOGS_DIR), "full backups don't exclude ${AppContainer.LOGS_DIR}/")
    }

    /**
     * L233: what the debrid services prepared for this device's plays, and the records of what its plays added to a
     * service account, belong to this device: each device removes only what it added (#318), and a listing names an
     * account id another device can't use. The whole folder, as Android reads it, its temp files with it.
     */
    @Test
    fun `the debrid folder is left out of cloud backups and device transfers on Android 12+`() {
        val extraction = rules("dataExtractionRules")
        listOf("cloud-backup", "device-transfer").forEach { section ->
            assertTrue(extraction.children(section).single().excludesFolder(AppContainer.DEBRID_DIR), "$section doesn't exclude ${AppContainer.DEBRID_DIR}/")
        }
    }

    @Test
    fun `the debrid folder is left out of full backups up to Android 11`() {
        assertTrue(rules("fullBackupContent").excludesFolder(AppContainer.DEBRID_DIR), "full backups don't exclude ${AppContainer.DEBRID_DIR}/")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
