package io.skjaere.usenet.app.plugins

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.BuildConfig

/**
 * Whose plugins this NZStreamer runs (L583, W158): one certificate, the beta key's, pinned in every build as
 * tools/beta-cert.sha256 publishes it. A loaded plugin can reach everything the app holds, every key among it, so there is
 * no second key and no "trust anyway". Device tests install plugins of their own build, signed as the app under test is:
 * [testSigner] lets that signer in, only in a debug build (L622).
 */
object PluginTrust {
    /** The beta key's certificate digest. */
    val pin: String = BuildConfig.PLUGIN_SIGNER

    /**
     * Test seam: the instrumented app's own signer, which the device tests' runner alone sets (AppTestRunner), so the
     * skeleton's debug builds load. Honoured only where BuildConfig.DEBUG: a debug phone with no test running, or any other
     * build, trusts the pin alone.
     */
    @VisibleForTesting
    @Volatile
    internal var testSigner: String? = null

    /** The digests a plugin's one signer may have. */
    fun signers(): Set<String> = setOfNotNull(pin, testSigner.takeIf { BuildConfig.DEBUG })
}
