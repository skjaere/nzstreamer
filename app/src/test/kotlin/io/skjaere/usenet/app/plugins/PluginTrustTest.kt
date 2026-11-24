package io.skjaere.usenet.app.plugins

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L583, L622, W158: every build trusts one signer, the beta key's, and a debug build also the device tests' own signer,
 * which only their runner sets. This runs in the debug and the release unit tests, and each answers its own way: which
 * build runs comes from the test task (UnitTestBuild), never from BuildConfig.
 */
class PluginTrustTest {
    @AfterEach
    fun putBack() {
        PluginTrust.testSigner = null
    }

    @Test
    fun `the pin is the beta key's certificate digest, trusted in every build`() {
        assertEquals(File("../tools/beta-cert.sha256").readText().trim(), PluginTrust.pin)
        assertEquals(setOf(PluginTrust.pin), PluginTrust.signers())
    }

    @Test
    fun `the test signer is trusted in a debug build alone`() {
        PluginTrust.testSigner = TEST
        val expected = if (UnitTestBuild.debug) setOf(PluginTrust.pin, TEST) else setOf(PluginTrust.pin)
        assertEquals(expected, PluginTrust.signers(), "a ${UnitTestBuild.type} build")
    }

    @Test
    fun `nothing but the device tests' runner sets the test signer`() {
        val sets = Regex("""\btestSigner\s*=(?!=)""")
        val declared = Regex("""\bvar testSigner\b""")
        val built = File("src").listFiles().orEmpty().filter { !it.name.startsWith("test") && it.name != "androidTest" }
        assertTrue(built.any { it.name == "main" }, "the app's source sets: ${built.map { it.name }}")
        val found = built.flatMap { set ->
            set.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    "${file.relativeTo(File("src")).path}:${i + 1}".takeIf { sets.containsMatchIn(line) && !declared.containsMatchIn(line) }
                }
            }.toList()
        }
        assertEquals(emptyList(), found, "set outside the device tests")
        val runner = File("src/androidTest/kotlin/io/skjaere/usenet/app/AppTestRunner.kt").readText()
        assertEquals(1, sets.findAll(runner).count(), "the device tests' runner sets it once")
        assertFalse(declared.containsMatchIn(runner))
    }

    private companion object {
        val TEST = "ef".repeat(32)
    }
}
