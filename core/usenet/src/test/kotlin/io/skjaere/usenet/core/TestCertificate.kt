package io.skjaere.usenet.core

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.fail

/**
 * A self-signed certificate naming [san] (a keytool subjectAltName, e.g. "dns:localhost"), made with
 * the test JDK's keytool, for a [FakeNntpServer] to present ([serverContext]), and a trust store that
 * trusts it. [trust] makes that trust store the JVM's default one, which ktor-nntp-client checks a
 * server's certificate against, until [distrust].
 */
class TestCertificate(san: String) : AutoCloseable {
    private val dir: Path = Files.createTempDirectory("usenet-tls")
    private val keyStore = dir.resolve("server.p12")
    private val trustStore = dir.resolve("trust.p12")
    private val saved = mutableMapOf<String, String?>()

    init {
        keytool(
            "-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650", "-dname", "CN=test server",
            "-ext", "SAN=$san", "-keystore", "$keyStore", "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD,
        )
        val certificate = dir.resolve("server.cer")
        keytool("-exportcert", "-alias", "server", "-keystore", "$keyStore", "-storepass", PASSWORD, "-file", "$certificate")
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", "$certificate", "-keystore", "$trustStore", "-storetype", "PKCS12", "-storepass", PASSWORD)
    }

    /** For a server presenting the certificate. */
    val serverContext: SSLContext by lazy {
        val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(keyStore).use { load(it, PASSWORD.toCharArray()) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD.toCharArray()) }.keyManagers
        SSLContext.getInstance("TLS").apply { init(managers, null, null) }
    }

    fun trust() {
        mapOf("javax.net.ssl.trustStore" to "$trustStore", "javax.net.ssl.trustStoreType" to "PKCS12", "javax.net.ssl.trustStorePassword" to PASSWORD)
            .forEach { (key, value) -> saved.getOrPut(key) { System.getProperty(key) }; System.setProperty(key, value) }
        loadDefaultTrustStore()
    }

    fun distrust() {
        saved.forEach { (key, value) -> if (value == null) System.clearProperty(key) else System.setProperty(key, value) }
        saved.clear()
        loadDefaultTrustStore()
    }

    /**
     * Has the JDK load the default trust store as the properties now name it, before connections
     * are opened at once: its cache (sun.security.ssl.TrustStoreManager) takes up a new trust store
     * under its lock but checks it without, so a connect racing that load can be handed the old
     * store's certificates. Nothing changes the trust store while the app runs.
     */
    private fun loadDefaultTrustStore() {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).init(null as KeyStore?)
    }

    override fun close() {
        distrust()
        dir.toFile().deleteRecursively()
    }

    private fun keytool(vararg args: String) {
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) fail("keytool ${args.first()} failed: $output")
    }

    private companion object {
        const val PASSWORD = "changeit"
    }
}
