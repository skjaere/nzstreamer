package io.skjaere.usenet.app.download

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L464: the debug build's `.debrid` file is the one thing in the app that points the services' clients elsewhere than the
 * services' own addresses, and only at the debrid fake. The app's sources are read as text, comments with the code:
 * [io.skjaere.usenet.app.debrid.ServiceEndpoints]' address is written in one place, its own, to the fake's address alone;
 * that write is asked only by the downloads' seam where `.debrid` is there ([DownloadDebug.pointServices]), which the
 * container calls only in a debug build; nothing writes it back to none, so a device run's closed address stays. And the
 * container's sweep of what plays and downloads added writes its counts through the same seams' trace.
 */
class DebridSeamSourcesTest {
    private val sources: Map<String, String> = File(APP).walkTopDown()
        .filter { it.isFile && it.extension == "kt" }.associate { it.relativeTo(File(APP)).path to it.readText() }

    /** Each line of the app's sources that [rule] finds, as `file:line: text`. */
    private fun found(rule: Regex): List<String> = sources.flatMap { (name, text) ->
        text.lines().mapIndexedNotNull { i, line -> "$name:${i + 1}: ${line.trim()}".takeIf { rule.containsMatchIn(line) } }
    }

    @Test
    fun `the services' address is written in one place, to the debrid fake's, and never back to none`() {
        assertTrue(sources.size > 300, "the app's sources read: ${sources.size}")
        assertEquals(emptyList(), found(WRITTEN_FROM_OUTSIDE), "written from outside ServiceEndpoints")
        val endpoints = sources.getValue(ENDPOINTS)
        assertEquals(listOf("override = ::fake"), endpoints.lines().map { it.trim() }.filter { OWN_WRITE.containsMatchIn(it) }, "ServiceEndpoints' own writes")
        assertEquals(emptyList(), found(WRITTEN_NONE), "written back to none")
        assertTrue(""""http://10.0.2.2:8097"""" in endpoints, "the fake's fixed address")
    }

    @Test
    fun `the fake's address is asked only by the downloads' seam where its file is, and that only in a debug build`() {
        val asked = found(POINTED)
        assertEquals(1, asked.size, "$asked")
        assertTrue(asked.single().startsWith("$SEAM:") && "if (debug.debrid)" in asked.single(), asked.single())
        val called = found(SEAM_CALLED)
        assertEquals(1, called.size, "$called")
        assertTrue(called.single().startsWith("$CONTAINER:") && "if (BuildConfig.DEBUG)" in called.single(), called.single())
    }

    @Test
    fun `the container's sweep of what plays and downloads added traces through the debug seams`() {
        val container = sources.getValue(CONTAINER)
        val made = container.substringAfter("val debridAdds: DebridAdds = DebridAdds(", "").substringBefore("\n    )\n")
        assertTrue("trace = { downloadDebug.trace(it) }" in made, made)
    }

    @Test
    fun `the rules find what they look for`() {
        assertTrue(WRITTEN_FROM_OUTSIDE.containsMatchIn("ServiceEndpoints.override = { \"https://elsewhere.invalid\" }"))
        assertFalse(WRITTEN_FROM_OUTSIDE.containsMatchIn("httpsOnly = { ServiceEndpoints.override == null }"))
        assertTrue(OWN_WRITE.containsMatchIn("override = ::fake"))
        assertFalse(OWN_WRITE.containsMatchIn("fun of(kind: ServiceKind): String = override?.invoke(kind) ?: when (kind) {"))
        assertFalse(OWN_WRITE.containsMatchIn("internal var override: ((ServiceKind) -> String)? = null"))
        assertTrue(WRITTEN_NONE.containsMatchIn("ServiceEndpoints.override = null"))
        assertTrue(WRITTEN_NONE.containsMatchIn("    override = null"))
        assertFalse(WRITTEN_NONE.containsMatchIn("internal var override: ((ServiceKind) -> String)? = null"))
        assertTrue(POINTED.containsMatchIn("if (debug.debrid) ServiceEndpoints.pointAtTheFake()"))
        assertFalse(POINTED.containsMatchIn("internal fun pointAtTheFake() {"))
        assertTrue(SEAM_CALLED.containsMatchIn("if (BuildConfig.DEBUG) DownloadDebug.pointServices(downloadDebug)"))
        assertFalse(SEAM_CALLED.containsMatchIn("fun pointServices(debug: DownloadDebug) {"))
    }

    private companion object {
        const val APP = "src/main/kotlin/io/skjaere/usenet/app"
        const val ENDPOINTS = "debrid/ServiceEndpoints.kt"
        const val SEAM = "download/DownloadDebug.kt"
        const val CONTAINER = "UsenetApplication.kt"

        val WRITTEN_FROM_OUTSIDE = Regex("""ServiceEndpoints\.override\s*=(?!=)""")
        val OWN_WRITE = Regex("""^override\s*=(?!=)""")
        val WRITTEN_NONE = Regex("""(^|[^:\w])override\s*=\s*null\b""")
        val POINTED = Regex("""(?<!fun )\bpointAtTheFake\(""")
        val SEAM_CALLED = Regex("""(?<!fun )\bpointServices\(""")
    }
}
