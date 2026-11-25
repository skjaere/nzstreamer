package io.skjaere.usenet.app.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * W168 (a), L656: a debug build fills an installed plugin's own secrets from local.properties (devSportarr.url and
 * devSportarr.key for Sportarr's address and key), as the build encodes them into BuildConfig.DEV_PLUGIN_SECRETS: a line
 * per secret, the plugin's id, the secret's name and its value by tabs. A new install alone is given them, each where none
 * is stored, in a debug build with the seeding on (device runs turn it off).
 *
 * Every encoding here is the test's own, given to each registry ([PluginRegistry.devSecrets]): the build's, which a debug
 * build carries with the developer's values, is never read. Secrets are compared by name, and their values by equality
 * alone, so no message holds one. This runs in the debug and the release unit tests, and each answers its own way
 * (UnitTestBuild): a release build gives nothing, whatever it is given.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginDevSecretsTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the build's encoding reads as each plugin's secrets, a line each`() {
        assertEquals(
            listOf(
                PluginDevSecrets.Entry("sportarr", "url", ADDRESS),
                PluginDevSecrets.Entry("sportarr", "key", KEY),
                PluginDevSecrets.Entry("harbor", "token", HARBOR_TOKEN),
            ),
            PluginDevSecrets.parse(ENCODED),
        )
        assertEquals(emptyList(), PluginDevSecrets.parse(""))
    }

    @Test
    fun `a line it can't use is left out, and the rest is read`() {
        val encoded = listOf(
            "sportarr\turl\t  $ADDRESS  ",
            "",
            "sportarr\tkey\t   ",
            "sportarr\tkey",
            "sportarr",
            "Sportarr\tkey\tan-id-outside-the-rule",
            "true\tkey\tan-id-outside-the-rule",
            "sportarr\tAPI Key\ta-name-outside-the-rule",
            "sportarr\t\ta-name-outside-the-rule",
            "sportarr\tkey\t$KEY",
            "sportarr\tkey\ta-second-value-for-one-name",
        ).joinToString("\n")
        assertEquals(
            listOf(PluginDevSecrets.Entry("sportarr", "url", ADDRESS), PluginDevSecrets.Entry("sportarr", "key", KEY)),
            PluginDevSecrets.parse(encoded),
        )
        assertEquals("Entry(sportarr, key)", PluginDevSecrets.Entry("sportarr", "key", KEY).toString(), "an entry never shows its value")
    }

    @Test
    fun `a new install of sportarr is given its address and key in a debug build, another plugin nothing, and a release build nothing`() = runTest {
        val h = RegistryHarness(dir, this)
        val registry = registry(h)
        assertIs<InstallResult.Installed>(h.install(registry, "skeleton", 1))
        assertStored(emptyMap(), h.secrets, "the skeleton, which the encoding doesn't name")
        assertIs<InstallResult.Installed>(h.install(registry, "sportarr", 1))
        if (UnitTestBuild.debug) {
            assertStored(mapOf(URL to ADDRESS, KEY_NAME to KEY), h.secrets, "a new install of sportarr")
        } else {
            assertStored(emptyMap(), h.secrets, "a ${UnitTestBuild.type} build")
        }
    }

    @Test
    fun `what is stored is never replaced, and only a new install is given anything`() = runTest {
        val h = RegistryHarness(dir, this)
        val registry = registry(h)
        // A key stored before the install, which the start's sweep has let be: the seeding puts nothing over it.
        h.secrets.values[KEY_NAME] = "a-key-the-user-saved"
        assertIs<InstallResult.Installed>(h.install(registry, "sportarr", 1))
        val firstInstall = if (UnitTestBuild.debug) mapOf(URL to ADDRESS, KEY_NAME to "a-key-the-user-saved") else mapOf(KEY_NAME to "a-key-the-user-saved")
        assertStored(firstInstall, h.secrets, "a new install over a stored key")

        // The user changes the key and clears the address; the same version again and an update give nothing back.
        h.secrets.values[KEY_NAME] = "a-key-the-user-changed"
        h.secrets.values.remove(URL)
        assertIs<InstallResult.Installed>(h.install(registry, "sportarr", 1))
        assertIs<InstallResult.UpdateWaiting>(h.install(registry, "sportarr", 2))
        assertStored(mapOf(KEY_NAME to "a-key-the-user-changed"), h.secrets, "the same version again, then an update")

        // Removed, its secrets go with it; installed again, it is a new install.
        registry.remove("sportarr")
        assertStored(emptyMap(), h.secrets, "removed")
        assertIs<InstallResult.Installed>(h.install(registry, "sportarr", 1))
        assertStored(if (UnitTestBuild.debug) mapOf(URL to ADDRESS, KEY_NAME to KEY) else emptyMap(), h.secrets, "installed again after a Remove")
    }

    @Test
    fun `nothing is given while the seeding is off`() = runTest {
        val before = PluginDevSecrets.seeding
        PluginDevSecrets.seeding = false
        try {
            val h = RegistryHarness(dir, this)
            assertIs<InstallResult.Installed>(h.install(registry(h), "sportarr", 1))
            assertStored(emptyMap(), h.secrets, "the seeding off, as a device run has it")
        } finally {
            PluginDevSecrets.seeding = before
        }
    }

    /** A registry over [h]'s files, started, its first screen asked, and given the test's own encoding. */
    private fun TestScope.registry(h: RegistryHarness): PluginRegistry = h.registry().apply {
        devSecrets = { ENCODED }
        start()
        firstActivity()
        advanceUntilIdle()
    }

    /** The secrets stored are [expected], by name, and each value is the one expected; no message gives a value. */
    private fun assertStored(expected: Map<String, String>, secrets: FakeSecrets, what: String) {
        assertEquals(expected.keys, secrets.values.keys, what)
        expected.forEach { (name, value) -> assertTrue(secrets.values[name] == value, "$what: $name holds another value than the one expected") }
    }

    private companion object {
        const val ADDRESS = "http://192.0.2.10:1867/sportarr"
        const val KEY = "an-invented-sportarr-key"
        const val HARBOR_TOKEN = "an-invented-harbor-token"
        const val URL = "plugin:sportarr:url"
        const val KEY_NAME = "plugin:sportarr:key"
        const val ENCODED = "sportarr\turl\t$ADDRESS\nsportarr\tkey\t$KEY\nharbor\ttoken\t$HARBOR_TOKEN"
    }
}
