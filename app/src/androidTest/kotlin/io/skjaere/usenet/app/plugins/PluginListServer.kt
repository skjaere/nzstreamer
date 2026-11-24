package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.plugin.PluginApi
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
import kotlin.concurrent.thread

/**
 * The plugin list and its releases on this device's loopback address, in the test's own process, standing for GitHub
 * (L622): /index.json as the test last set it ([list]), and each release file the list names, the skeleton's builds from
 * the test APK's assets, at GitHub's paths on this server ([source]). The path of each request is kept ([requests]).
 * Just enough of HTTP for one GET at a time; never a real service.
 */
class PluginListServer : AutoCloseable {
    /**
     * One version as the list gives it: [build]'s file, released as [tag] ("1.0.0"), listed as [versionCode] with
     * [build]'s own unless given, its SHA-256 the file's unless [sha256] says otherwise.
     */
    class Listed(val build: SkeletonBuild, val tag: String, val versionCode: Long = build.versionCode, val sha256: String? = null)

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int = server.localPort

    /** Where the app reads plugins from while this serves them. */
    val source: PluginSource = PluginSource.loopback(port)
    val requests = CopyOnWriteArrayList<String>()

    @Volatile
    private var index = ByteArray(0)
    private val files = ConcurrentHashMap<String, ByteArray>()
    private var lists = 0L

    init {
        thread(isDaemon = true, name = "plugin-list") {
            while (!server.isClosed) {
                try {
                    server.accept().use(::answer)
                } catch (e: IOException) {
                    // Closed, or the client went before its answer was sent.
                }
            }
        }
    }

    /**
     * Lists the skeleton with [versions], its [withdrawn] versionCodes, made a second after the list before it, so the app
     * never reads it as an older list. Each version's file is served at its address.
     */
    fun list(vararg versions: Listed, withdrawn: Set<Long> = emptySet()) {
        lists++
        val libs = PluginCompat.parseLibs(BuildConfig.PLUGIN_HOST_LIBS)!!
        val entries = buildJsonArray {
            versions.forEach { version ->
                val bytes = version.build.bytes()
                files[path(version.tag)] = bytes
                add(
                    buildJsonObject {
                        put("url", "http://127.0.0.1:$port${path(version.tag)}")
                        put("sha256", version.sha256 ?: Sha256.of(bytes))
                        put("versionName", "${version.versionCode}")
                        put("versionCode", version.versionCode)
                        put("api", "${PluginApi.MAJOR}.${PluginApi.MINOR + if (version.build == SkeletonBuild.Newer) 1 else 0}")
                        put("libs", buildJsonObject { libs.forEach { (name, value) -> put(name, value) } })
                        put("minSdk", 26)
                        put("size", bytes.size)
                    },
                )
            }
        }
        val list = buildJsonObject {
            put("format", 1)
            put("generated", GENERATED.plusSeconds(lists).toString())
            put(
                "plugins",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", SKELETON)
                            put("package", PluginIds.packageOf(SKELETON))
                            put("name", "Skeleton")
                            put("summary", "The plugin platform's own example, with invented words.")
                            put("withdrawn", buildJsonArray { withdrawn.forEach { add(JsonPrimitive(it)) } })
                            put("versions", entries)
                        },
                    )
                },
            )
        }
        index = list.toString().toByteArray()
    }

    /** Where release [tag] of the skeleton is served, as GitHub's path. */
    fun path(tag: String) = "${PluginSource.RELEASES}plugin-$SKELETON-v$tag/$SKELETON.apk"

    private fun answer(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val line = input.readLine().orEmpty()
        generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
        val path = line.split(' ').getOrNull(1).orEmpty()
        // Kept before it is answered, so a request is there by the time its answer is read.
        requests += path
        val body = if (path == "/index.json") index else files[path]
        val head = if (body == null) "HTTP/1.1 404 Not Found\r\nContent-Length: 0" else "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}"
        socket.getOutputStream().apply {
            write("$head\r\nConnection: close\r\n\r\n".toByteArray())
            body?.let(::write)
            flush()
        }
    }

    override fun close() = server.close()

    private companion object {
        val GENERATED: Instant = Instant.parse("2026-11-24T10:00:00Z")
    }
}
