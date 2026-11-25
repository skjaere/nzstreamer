package io.skjaere.usenet.app.plugins

import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.plugin.PluginApi
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The plugin list and its releases on this device's loopback address, in the test's own process, standing for GitHub
 * (L622): /index.json as the test last set it ([list]), and each release file the list names, the plugins' builds from
 * the test APK's assets (the skeleton's, and others' such as the Sportarr plugin's, L657), at GitHub's paths on this
 * server (the app's [PluginListServer.source]). The path of each request is kept ([requests]). A download can be held
 * half-way ([holdDownloads]), as a slow one is under way. Just enough of HTTP for a GET per connection, each answered on
 * its own thread; never a real service.
 */
class PluginListServer : AutoCloseable {
    /**
     * A build the list can offer: its plugin's [id], [name] and [summary] as the list gives them, its [versionCode] as
     * built, the versionName the list gives it, the plugin API it was built against ([api]), and its file ([bytes]).
     */
    sealed interface Source {
        val id: String
        val name: String
        val summary: String
        val versionCode: Long
        val api: String

        /** The versionName the list gives this build when it lists it as [versionCode]. */
        fun versionName(versionCode: Long): String

        fun bytes(): ByteArray
    }

    /**
     * One of the skeleton's builds, listed as the skeleton's list has always listed them: its name and summary, the
     * listed versionCode's digits as its versionName, and newer's the next plugin API minor.
     */
    data class Skeleton(val build: SkeletonBuild) : Source {
        override val id = SKELETON
        override val name = "Skeleton"
        override val summary = "The plugin platform's own example, with invented words."
        override val versionCode = build.versionCode
        override val api = "${PluginApi.MAJOR}.${PluginApi.MINOR + if (build == SkeletonBuild.Newer) 1 else 0}"

        override fun versionName(versionCode: Long) = "$versionCode"

        override fun bytes() = build.bytes()
    }

    /**
     * A plugin's build the test APK's assets hold as [asset], listed as its release would be: its [id], its
     * listing's [name] and [summary], its [versionName] and [versionCode] as built (version.txt's, by the plugin
     * convention), built against this host's plugin API.
     */
    class Asset(
        private val asset: String,
        override val id: String,
        override val name: String,
        override val summary: String,
        private val versionName: String,
        override val versionCode: Long,
    ) : Source {
        override val api = "${PluginApi.MAJOR}.${PluginApi.MINOR}"

        override fun versionName(versionCode: Long) = versionName

        override fun bytes(): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open(asset).use { it.readBytes() }
    }

    /**
     * One version as the list gives it: [source]'s file, released as [tag] ("1.0.0"), listed as [versionCode] with
     * [source]'s own unless given, its SHA-256 the file's unless [sha256] says otherwise, its plugin API the one it was
     * built against unless [api] says otherwise; its file not there at all where not [served].
     */
    class Listed(
        val source: Source,
        val tag: String,
        val versionCode: Long = source.versionCode,
        val sha256: String? = null,
        val api: String? = null,
        val served: Boolean = true,
    ) {
        /** One of the skeleton's builds, as its own classes list them. */
        constructor(
            build: SkeletonBuild,
            tag: String,
            versionCode: Long = build.versionCode,
            sha256: String? = null,
            api: String? = null,
            served: Boolean = true,
        ) : this(Skeleton(build), tag, versionCode, sha256, api, served)
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort

    /** Where the app reads plugins from while this serves them. */
    val source: PluginSource = PluginSource.loopback(port)
    val requests = CopyOnWriteArrayList<String>()

    @Volatile
    private var index = ByteArray(0)
    private val files = ConcurrentHashMap<String, ByteArray>()
    private var lists = 0L

    @Volatile
    private var held: CountDownLatch? = null

    init {
        thread(isDaemon = true, name = "plugin-list") {
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    thread(isDaemon = true, name = "plugin-list-answer") {
                        try {
                            socket.use(::answer)
                        } catch (e: IOException) {
                            // The client went before its answer was sent: a download cancelled.
                        }
                    }
                } catch (e: IOException) {
                    // Closed.
                }
            }
        }
    }

    /** From now on, each release file is sent half-way, then held until [release]: a download the app shows under way. */
    fun holdDownloads() {
        held = CountDownLatch(1)
    }

    /** The files held half-way are sent to their end, and the next ones whole; once the server is closed, never. */
    fun release() {
        held?.countDown()
        held = null
    }

    /**
     * Lists [versions], each under its plugin, the plugins in the order they first come (the skeleton with none, where
     * no version is given), each with the [withdrawn] versionCodes; made a second after the list before it, so the app
     * never reads it as an older list. Each version's file is served at its address.
     */
    fun list(vararg versions: Listed, withdrawn: Set<Long> = emptySet()) {
        lists++
        val libs = PluginCompat.parseLibs(BuildConfig.PLUGIN_HOST_LIBS)!!
        // No version given: the skeleton with none, as this list has always said it.
        val plugins = versions.groupBy { it.source.id }.ifEmpty { mapOf(SKELETON to emptyList()) }
        val list = buildJsonObject {
            put("format", 1)
            put("generated", GENERATED.plusSeconds(lists).toString())
            put(
                "plugins",
                buildJsonArray {
                    plugins.forEach { (id, listed) ->
                        val entries = buildJsonArray { listed.forEach { add(entry(it, libs)) } }
                        // Its words are its versions' source's; the skeleton's own, where it has none.
                        val source = listed.firstOrNull()?.source ?: Skeleton(SkeletonBuild.V1)
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("package", PluginIds.packageOf(id))
                                put("name", source.name)
                                put("summary", source.summary)
                                put("withdrawn", buildJsonArray { withdrawn.forEach { add(JsonPrimitive(it)) } })
                                put("versions", entries)
                            },
                        )
                    }
                },
            )
        }
        index = list.toString().toByteArray()
    }

    /** [version] as its plugin's versions list it, its file served at its address from now on where [Listed.served]. */
    private fun entry(version: Listed, libs: Map<String, String>): JsonObject {
        val bytes = version.source.bytes()
        val path = path(version.tag, version.source.id)
        if (version.served) files[path] = bytes else files.remove(path)
        return buildJsonObject {
            put("url", "http://127.0.0.1:$port$path")
            put("sha256", version.sha256 ?: Sha256.of(bytes))
            put("versionName", version.source.versionName(version.versionCode))
            put("versionCode", version.versionCode)
            put("api", version.api ?: version.source.api)
            put("libs", buildJsonObject { libs.forEach { (name, value) -> put(name, value) } })
            put("minSdk", 26)
            put("size", bytes.size)
        }
    }

    /** Where release [tag] of plugin [id] (the skeleton, unless given) is served: GitHub's path, the id in both places. */
    fun path(tag: String, id: String = SKELETON) = "${PluginSource.RELEASES}plugin-$id-v$tag/$id.apk"

    private fun answer(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val line = input.readLine().orEmpty()
        generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
        val path = line.split(' ').getOrNull(1).orEmpty()
        // Kept before it is answered, so a request is there by the time its answer is read.
        requests += path
        val body = if (path == "/index.json") index else files[path]
        val head = if (body == null) "HTTP/1.1 404 Not Found\r\nContent-Length: 0" else "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}"
        val hold = held.takeIf { path != "/index.json" && body != null }
        socket.getOutputStream().apply {
            write("$head\r\nConnection: close\r\n\r\n".toByteArray())
            if (body != null && hold != null) {
                write(body, 0, body.size / 2)
                flush()
                // Never for ever: a test that forgot its release ends the download, not the run. Closed meanwhile, the
                // server sends no more: the download fails, and nothing is installed after the test.
                hold.await(HOLD_S, TimeUnit.SECONDS)
                if (server.isClosed) return
                write(body, body.size / 2, body.size - body.size / 2)
            } else {
                body?.let(::write)
            }
            flush()
        }
    }

    override fun close() {
        server.close()
        release()
    }

    private companion object {
        val GENERATED: Instant = Instant.parse("2026-11-24T10:00:00Z")

        /** The longest a held file waits for its release. */
        const val HOLD_S = 30L
    }
}
