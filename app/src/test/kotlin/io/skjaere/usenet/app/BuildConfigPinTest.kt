package io.skjaere.usenet.app

import io.skjaere.usenet.app.plugins.UnitTestBuild
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What every build knows of plugins (L583, L615): the one signer a plugin may have, the beta key's certificate digest as
 * tools/beta-cert.sha256 holds it, and the shared libraries plugins take from the app, by name at the version catalog's
 * versions, in L615's order. A plugin's own build stamps the same list from the same catalog, so a plugin built against
 * newer ones is told apart (L584). This reads both files itself; the app's unit tests declare them inputs, so a change to
 * either alone runs it again. BuildConfig's fields are read at run time: their constants, compiled into this class,
 * could be an earlier build's. And no build but debug carries a plugin's dev settings (W168 a, L656): the release build,
 * which bench and both betas start from, has none. That field is read in a release build's run alone: a debug build's
 * holds the developer's own values.
 */
class BuildConfigPinTest {
    private fun buildConfig(field: String): Any? = BuildConfig::class.java.getField(field).get(null)

    @Test
    fun `the plugin signer is the beta key's certificate digest, in every build`() {
        val digest = File("../tools/beta-cert.sha256").readText().trim()
        assertTrue(Regex("[0-9a-f]{64}").matches(digest), "tools/beta-cert.sha256 holds no SHA-256 digest")
        assertEquals(digest, buildConfig("PLUGIN_SIGNER"))
    }

    @Test
    fun `the host's libraries are L615's, at the version catalog's versions`() {
        val catalog = File("../gradle/libs.versions.toml").readLines()
        val versions = catalog.dropWhile { it.trim() != "[versions]" }.drop(1).takeWhile { !it.startsWith("[") }
            .mapNotNull { Regex("""^([a-z0-9-]+) = "([^"]+)"""").find(it)?.destructured }
            .associate { (name, version) -> name to version }
        fun version(name: String) = versions[name] ?: fail("gradle/libs.versions.toml's [versions] has no $name")
        // kotlinx-serialization-json carries its version in its library line.
        val serialization = catalog.firstNotNullOfOrNull { Regex("""^kotlinx-serialization-json = \{.*version = "([^"]+)"""").find(it)?.groupValues?.get(1) }
            ?: fail("gradle/libs.versions.toml has no kotlinx-serialization-json with a version")
        assertEquals(
            listOf(
                "kotlin-stdlib=${version("kotlin")}",
                "kotlinx-coroutines=${version("coroutines")}",
                "kotlinx-serialization-json=$serialization",
                "compose-bom=${version("compose-bom")}",
                "lifecycle-viewmodel-compose=${version("lifecycle")}",
                "activity-compose=${version("activity-compose")}",
                "slf4j-api=${version("slf4j")}",
            ).joinToString(","),
            buildConfig("PLUGIN_HOST_LIBS"),
        )
    }

    @Test
    fun `a release build carries no plugin's dev settings`() {
        assumeFalse(UnitTestBuild.debug, "a debug build carries local.properties' plugin settings: never read here")
        val encoded = buildConfig("DEV_PLUGIN_SECRETS") as String
        // Should one be there, the message names its plugin and setting alone, never its value.
        val named = encoded.lines().filter { it.isNotBlank() }.map { line -> line.split('\t').take(2).joinToString(" ") }
        assertTrue(encoded.isEmpty(), "the ${UnitTestBuild.type} build carries plugin settings: $named")
    }
}
