package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.plugin.PluginApi
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L584: NZStreamer runs a plugin built against the same API major as its own and a minor not above its own, and shared
 * libraries not newer than its own (for every one but the Compose BOM, of the same major too). A library the plugin names
 * that the host doesn't know refuses; one the host knows and the plugin leaves out is fine.
 */
class PluginCompatTest {
    private val hostLibs = "kotlin-stdlib=2.3.21,kotlinx-coroutines=1.11.0,kotlinx-serialization-json=1.11.0," +
        "compose-bom=2026.06.01,lifecycle-viewmodel-compose=2.10.0,activity-compose=1.13.0,slf4j-api=2.0.20"
    private val compat = PluginCompat(hostMajor = 1, hostMinor = 2, hostLibs = hostLibs)

    private fun libs(vararg changed: Pair<String, String>): Map<String, String> =
        PluginCompat.parseLibs(hostLibs)!! + changed

    @Test
    fun `the same major with the same or a lower minor is accepted, a higher minor or another major refused`() {
        assertTrue(compat.apiAccepted(1, 2))
        assertTrue(compat.apiAccepted(1, 0))
        assertFalse(compat.apiAccepted(1, 3), "a higher minor")
        assertFalse(compat.apiAccepted(2, 0), "a higher major")
        assertFalse(compat.apiAccepted(0, 9), "a lower major")
        assertFalse(compat.apiAccepted(1, -1), "a minor below 0")
    }

    @Test
    fun `minors compare as numbers, so 1 point 10 is above 1 point 9`() {
        assertFalse(PluginCompat(1, 9, hostLibs).apiAccepted(1, 10))
        assertTrue(PluginCompat(1, 10, hostLibs).apiAccepted(1, 9))
    }

    @Test
    fun `each library older or equal is accepted, newer refused, another major refused`() {
        assertTrue(compat.libsAccepted(libs()), "every library the host's")
        assertTrue(compat.libsAccepted(libs("kotlinx-coroutines" to "1.10.2")), "older")
        assertTrue(compat.libsAccepted(libs("kotlin-stdlib" to "2.3.0")), "an older patch")
        assertFalse(compat.libsAccepted(libs("kotlin-stdlib" to "2.3.22")), "a newer patch")
        assertFalse(compat.libsAccepted(libs("activity-compose" to "1.14.0")), "a newer minor")
        assertFalse(compat.libsAccepted(libs("slf4j-api" to "1.7.36")), "an older major")
        assertFalse(compat.libsAccepted(libs("lifecycle-viewmodel-compose" to "3.0.0")), "a newer major")
        listOf("kotlin-stdlib", "kotlinx-coroutines", "kotlinx-serialization-json", "lifecycle-viewmodel-compose", "activity-compose", "slf4j-api")
            .forEach { assertFalse(compat.libsAccepted(libs(it to "0.1.0")), "$it of another major") }
    }

    @Test
    fun `the Compose BOM is accepted when not newer, whatever its year`() {
        assertTrue(compat.libsAccepted(libs("compose-bom" to "2025.12.01")), "last year's")
        assertTrue(compat.libsAccepted(libs("compose-bom" to "2026.06.01")), "the host's")
        assertFalse(compat.libsAccepted(libs("compose-bom" to "2026.08.00")), "a newer one")
        assertFalse(compat.libsAccepted(libs("compose-bom" to "2027.01.00")), "next year's")
    }

    @Test
    fun `a library the host doesn't know refuses, one the plugin leaves out is fine`() {
        assertFalse(compat.libsAccepted(libs("ktor-client-core" to "3.0.0")))
        assertTrue(compat.libsAccepted(mapOf("kotlin-stdlib" to "2.3.21")))
        assertTrue(compat.libsAccepted(emptyMap()))
    }

    @Test
    fun `a version or a list that doesn't read is refused`() {
        assertFalse(compat.libsAccepted(libs("kotlin-stdlib" to "two")), "no number")
        assertFalse(compat.libsAccepted(libs("kotlin-stdlib" to "")), "empty")
        // A pre-release comes before its release, as Kotlin's and AndroidX's versions are ordered.
        assertTrue(compat.libsAccepted(libs("kotlin-stdlib" to "2.3.21-RC")), "a pre-release of the host's own")
        assertFalse(compat.libsAccepted(libs("kotlin-stdlib" to "2.3.22-RC")), "a pre-release of a newer one")
        assertFalse(PluginCompat(1, 0, "kotlin-stdlib=2.3.21-RC").libsAccepted(mapOf("kotlin-stdlib" to "2.3.21")), "the release a host has a pre-release of")
        assertFalse(PluginCompat(1, 0, "kotlin-stdlib=2.3.21-RC").libsAccepted(mapOf("kotlin-stdlib" to "2.3.21-beta1")), "another pre-release of it")
        assertNull(PluginCompat.parseLibs("kotlin-stdlib"), "no version")
        assertNull(PluginCompat.parseLibs("kotlin-stdlib=2.3.21,kotlin-stdlib=2.3.0"), "named twice")
        assertNull(PluginCompat.parseLibs("=2.3.21"), "no name")
        assertEquals(emptyMap(), PluginCompat.parseLibs(""))
        assertEquals(mapOf("a" to "1.0", "b" to "2.0"), PluginCompat.parseLibs("a=1.0,b=2.0"))
    }

    @Test
    fun `the whole check takes the stamp and the meta-data's list`() {
        assertTrue(compat.accepts(1, 1, hostLibs))
        assertFalse(compat.accepts(1, 3, hostLibs), "the API")
        assertFalse(compat.accepts(1, 1, "kotlin-stdlib=9.0.0"), "a library")
        assertFalse(compat.accepts(1, 1, null), "no list")
        assertFalse(compat.accepts(1, 1, "kotlin-stdlib"), "a list that doesn't read")
    }

    @Test
    fun `the host's own are this build's`() {
        assertTrue(PluginCompat().accepts(PluginApi.MAJOR, PluginApi.MINOR, BuildConfig.PLUGIN_HOST_LIBS))
        assertFalse(PluginCompat().accepts(PluginApi.MAJOR, PluginApi.MINOR + 1, BuildConfig.PLUGIN_HOST_LIBS))
    }
}
