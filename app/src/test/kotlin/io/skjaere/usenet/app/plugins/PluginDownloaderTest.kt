package io.skjaere.usenet.app.plugins

import io.ktor.client.HttpClient
import io.skjaere.usenet.debrid.ServiceHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L633: a plugin's file comes from the list's address into cacheDir/plugins/.download, the space for it and its stored
 * copy looked at before anything is asked; https on every hop (http only to the loopback address a test's source allows),
 * three redirects at most, no more than the list's size read, and its SHA-256 the list's before anything reads it. Cancel
 * keeps nothing.
 *
 * The server is this test's own on the loopback address, and the source is given to the downloader directly, so these pass
 * in the debug and the release unit-test runs alike (L622).
 */
class PluginDownloaderTest {
    @TempDir
    lateinit var dir: File

    private val http: HttpClient = ServiceHttp.defaultHttpClient()
    private val servers = mutableListOf<LoopbackHttp>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val folder get() = File(dir, "cache/plugins/.download")

    @AfterEach
    fun close() {
        scope.cancel()
        http.close()
        servers.forEach { it.close() }
    }

    private fun server(answer: (LoopbackHttp.Request) -> LoopbackHttp.Response) = LoopbackHttp(answer).also { servers += it }

    private fun downloader(server: LoopbackHttp, space: () -> Long = { Long.MAX_VALUE }) =
        PluginDownloader(PluginSource.loopback(server.port), http, folder, space)

    /** Harbor's version [code] as the list gives it on [server]'s releases: its file [bytes], its size and SHA-256 the list's. */
    private fun version(server: LoopbackHttp, bytes: ByteArray, code: Long = 1, size: Long = bytes.size.toLong(), sha256: String = Sha256.of(bytes)) =
        ListedVersion(code, "$code", 1, 0, LIBS, 26, "http://127.0.0.1:${server.port}${apkPath(code)}", size, sha256)

    @Test
    fun `the file comes over three redirects, as NZStreamer, every byte counted`() = runBlocking<Unit> {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val server = server { request ->
            when (request.path) {
                apkPath(1) -> redirect("/assets/one")
                "/assets/one" -> redirect("http://127.0.0.1:${servers.single().port}/assets/two")
                "/assets/two" -> redirect("three")
                "/assets/three" -> LoopbackHttp.Response(200, bytes)
                else -> LoopbackHttp.Response(404)
            }
        }
        val heard = mutableListOf<Long>()
        val done = assertIs<Download.Done>(downloader(server).download("harbor", version(server, bytes)) { heard += it })
        assertContentEquals(bytes, done.file.readBytes())
        assertEquals(folder, done.file.parentFile, "in cacheDir/plugins/.download")
        assertEquals(listOf(apkPath(1), "/assets/one", "/assets/two", "/assets/three"), server.requests.map { it.path })
        assertEquals(listOf("NZStreamer"), server.requests.map { it.headers["user-agent"] }.distinct())
        assertEquals(bytes.size.toLong(), heard.last())
        assertEquals(heard.sorted(), heard, "the bytes read so far, as they come")
    }

    @Test
    fun `a hop that isn't https is refused, with no request to it`() = runBlocking<Unit> {
        val bytes = "harbor 1".toByteArray()
        val server = server { redirect("http://example.invalid/harbor.apk") }
        val failed = assertIs<Download.Failed>(downloader(server).download("harbor", version(server, bytes)) {})
        assertEquals(DownloadFailure.Insecure, failed.kind)
        assertEquals(listOf(apkPath(1)), server.requests.map { it.path }, "the http hop never asked")

        // A source that doesn't let http through to the loopback address refuses the first hop too, asking nothing.
        val strict = PluginSource("http://127.0.0.1:${server.port}/index.json", releases = "http://127.0.0.1:${server.port}", loopbackHttp = false)
        val first = assertIs<Download.Failed>(PluginDownloader(strict, http, folder) { Long.MAX_VALUE }.download("harbor", version(server, bytes)) {})
        assertEquals(DownloadFailure.Insecure, first.kind)
        assertEquals(1, server.requests.size)
        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }

    @Test
    fun `a fourth redirect is refused`() = runBlocking<Unit> {
        val bytes = "harbor 1".toByteArray()
        val server = server { request ->
            when (request.path) {
                apkPath(1) -> redirect("/hop/1")
                "/hop/1" -> redirect("/hop/2")
                "/hop/2" -> redirect("/hop/3")
                "/hop/3" -> redirect("/hop/4")
                else -> LoopbackHttp.Response(200, bytes)
            }
        }
        val failed = assertIs<Download.Failed>(downloader(server).download("harbor", version(server, bytes)) {})
        assertEquals(DownloadFailure.Redirects, failed.kind)
        assertEquals(listOf(apkPath(1), "/hop/1", "/hop/2", "/hop/3"), server.requests.map { it.path }, "the fourth redirect not followed")
        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }

    /** Cut: nothing past the list's size is written, nor counted as come; one byte past it, the download is refused. */
    @Test
    fun `a body longer than the list's size is cut and refused, its length given or not`() = runBlocking<Unit> {
        val listed = "harbor 1".toByteArray()
        val longer = ByteArray(300_000) { 'x'.code.toByte() }
        listOf(false, true).forEach { lengthless ->
            val server = server { LoopbackHttp.Response(200, longer, lengthless = lengthless) }
            val heard = mutableListOf<Long>()
            val failed = assertIs<Download.Failed>(downloader(server).download("harbor", version(server, listed)) { heard += it })
            assertEquals(DownloadFailure.TooLarge, failed.kind, if (lengthless) "read to the close" else "with its Content-Length")
            assertTrue(heard.all { it <= listed.size }, "no more than the list's size taken: $heard")
            assertEquals(emptyList(), folder.list().orEmpty().toList())
        }
    }

    @Test
    fun `a file whose SHA-256 isn't the list's is refused before the plugin's checks are asked`() = runBlocking<Unit> {
        val server = server { LoopbackHttp.Response(200, pluginText("harbor", 1).toByteArray()) }
        val listed = version(server, pluginText("harbor", 1).toByteArray(), sha256 = Sha256.of("harbor 1, as the list knew it".toByteArray()))
        val secrets = FakeSecrets()
        val store = PluginStore(File(dir, "files"), File(dir, "cache"), secrets)
        val loading = FakeLoading()
        val registry = PluginRegistry(store, loading, PluginHosts(store, secrets, main = Dispatchers.Unconfined), FakeGuard(), scope, io = Dispatchers.IO)
        registry.start()
        registry.firstActivity()
        val index = PluginIndex(Instant.EPOCH, listOf(ListedPlugin("harbor", "Harbor Lights", "", emptySet(), listOf(listed))), emptyList())
        val installer = PluginInstaller(
            registry, MutableStateFlow(PluginListState(index = index)), emptyFlow(), { downloader(server) }, { Long.MAX_VALUE }, scope, { false },
            sdk = 34, compat = PluginCompat(1, 0, HOST_LIBS),
        )

        withTimeout(WAIT_MS) { assertNotNull(installer.install("harbor"), "an install started").join() }
        assertEquals("The file doesn't match the list, so nothing was installed.", assertIs<InstallState.Failed>(installer.states.value["harbor"]).line)
        assertEquals(emptyList(), loading.verified, "the archive's checks never asked")
        assertEquals(emptyList(), registry.plugins.value.orEmpty().map { it.id })
        assertFalse(File(dir, "files/plugins/harbor").exists())
        assertEquals(emptyList(), folder.list().orEmpty().toList(), "the download deleted")
    }

    @Test
    fun `too little space is refused before anything is asked, with the MB to free`() = runBlocking<Unit> {
        val server = server { LoopbackHttp.Response(200, ByteArray(700_000)) }
        val listed = version(server, ByteArray(700_000))
        val short = assertIs<Download.NoSpace>(downloader(server) { 700_000 }.download("harbor", listed) {})
        assertEquals(700_000, short.toFree, "room for the download and its stored copy")
        assertEquals("Not enough space on this phone. Free 0.7 MB, then try again.", PluginInstaller.spaceLine(short.toFree, television = false))
        assertEquals("Not enough space on this TV. Free 0.7 MB, then try again.", PluginInstaller.spaceLine(short.toFree, television = true))
        val byte = assertIs<Download.NoSpace>(downloader(server) { 1_399_999 }.download("harbor", listed) {})
        assertEquals(1, byte.toFree)
        assertEquals("Not enough space on this phone. Free 0.1 MB, then try again.", PluginInstaller.spaceLine(byte.toFree, television = false))
        assertEquals(emptyList(), server.requests.map { it.path }, "nothing asked")
        assertIs<Download.Done>(downloader(server) { 1_400_000 }.download("harbor", listed) {})
    }

    /** The app's words are English with a decimal point, as the card's own size line is, whatever the device's language. */
    @Test
    fun `the space line's MB keep their decimal point on a device in another language`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("Not enough space on this phone. Free 0.7 MB, then try again.", PluginInstaller.spaceLine(700_000, television = false))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `a write that fails for want of space says so, and another failed write doesn't`() = runBlocking<Unit> {
        val bytes = "harbor 1".toByteArray()
        val server = server { LoopbackHttp.Response(200, bytes) }
        // A file where the download folder should be: nothing can be written there.
        folder.parentFile!!.mkdirs()
        folder.writeText("in the way")
        var free = 1_000L
        val full = assertIs<Download.NoSpace>(downloader(server) { free.also { free = 3 } }.download("harbor", version(server, bytes)) {})
        assertEquals(2L * bytes.size - 3, full.toFree)
        val failed = assertIs<Download.Failed>(downloader(server).download("harbor", version(server, bytes)) {})
        assertEquals(DownloadFailure.Write, failed.kind)
    }

    @Test
    fun `an address the list's rule doesn't hold is refused, with no request`() = runBlocking<Unit> {
        val bytes = "harbor 1".toByteArray()
        val server = server { LoopbackHttp.Response(200, bytes) }
        val elsewhere = version(server, bytes).let { listed ->
            ListedVersion(1, "1", 1, 0, LIBS, 26, "http://127.0.0.1:${server.port}/skjaere/nzstreamer/releases/download/plugin-kestrel-v1.0.0/kestrel.apk", listed.size, listed.sha256)
        }
        assertEquals(DownloadFailure.Address, assertIs<Download.Failed>(downloader(server).download("harbor", elsewhere) {}).kind)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun `cancelled mid-download, it leaves no file`() = runBlocking<Unit> {
        val bytes = ByteArray(400_000) { (it % 7).toByte() }
        val server = server { LoopbackHttp.Response(200, bytes, stallAfter = 100_000) }
        val started = CompletableDeferred<Unit>()
        val download = scope.async { downloader(server).download("harbor", version(server, bytes)) { started.complete(Unit) } }
        withTimeout(WAIT_MS) { started.await() }
        assertTrue(File(folder, "harbor-1.apk").isFile, "its first bytes written")
        withTimeout(WAIT_MS) { download.cancelAndJoin() }
        assertEquals(emptyList(), folder.list().orEmpty().toList())
    }

    @Test
    fun `a file a download left as its process died goes as the next one starts`() = runBlocking<Unit> {
        folder.mkdirs()
        File(folder, "kestrel-3.apk").writeText("half a file")
        val bytes = "harbor 1".toByteArray()
        val server = server { LoopbackHttp.Response(200, bytes) }
        val done = assertIs<Download.Done>(downloader(server).download("harbor", version(server, bytes)) {})
        assertEquals(listOf(done.file.name), folder.list().orEmpty().toList())
    }

    private companion object {
        val LIBS = PluginCompat.parseLibs(HOST_LIBS)!!

        /** How long a download over this machine's loopback address may take before the test gives up on it. */
        const val WAIT_MS = 20_000L

        fun apkPath(code: Long) = "/skjaere/nzstreamer/releases/download/plugin-harbor-v$code.0.0/harbor.apk"

        fun redirect(to: String) = LoopbackHttp.Response(302, headers = mapOf("Location" to to))
    }
}
