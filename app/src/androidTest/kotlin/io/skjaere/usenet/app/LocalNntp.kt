package io.skjaere.usenet.app

import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.yenc.YencEncoder
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * An NNTP server inside the test's process, for the device tests' own fixtures: it serves files as a poster posts them
 * (yEnc in parts, as tools/mock-seed's MockSeeder does) under message ids of its own, so the shared mock NNTP server is
 * neither read nor written, and a test runs the same on every emulator. An article can be made [missing]: BODY answers
 * 430 for it while STAT still says 223, as a provider whose copy expired after the availability check's sample passed it,
 * the case a play meets mid-stream. Plain TCP on the IPv4 loopback (Android's own loopback address is IPv6, which the
 * engine isn't given), on a port of its own; closed by [close]. Commands are answered as core:usenet's FakeNntpServer
 * answers them, logins by anyone.
 *
 * Its connections can die after sitting idle, as a NAT, a VPN or a provider ends a flow nobody used for a while (the
 * libraries' and core:usenet's fakes do the same). Each connection notes when its last answer ended (the greeting's, at
 * first). A command that comes once the connection has been idle [idleAfterMs] or longer finds it dead for good, in the
 * way [onIdle] says. Any answer keeps a connection alive, a DATE's as any other, as a keepalive refreshes a NAT's entry.
 * Null [idleAfterMs]: none die. Only [affected] of every [outOf] connections can die, by accept order from 0 (K of M):
 * connection i when `i % outOf < affected`. On loopback, as through a NAT that dropped the flow, a silent connection's
 * writes are still taken: the client sees no answer, not an error.
 */
class LocalNntp(
    private val idleAfterMs: Long? = null,
    private val onIdle: OnIdle = OnIdle.SILENT,
    private val affected: Int = 1,
    private val outOf: Int = 1,
) : Closeable {
    /** How a connection dies once idle ([idleAfterMs]). */
    enum class OnIdle {
        /** Reads and drops whatever comes, never writes, never closes. */
        SILENT,

        /** Closes with RST (SO_LINGER 0): the client's next read fails with a reset. */
        RST,

        /** Closes with FIN: the client's next read finds the stream's end. */
        FIN,

        /** Writes "400 idle timeout", then closes with FIN: the client reads the 400 as its command's answer. */
        KICK,
    }

    /** A file as posted: its [name], bytes and parts, and each part's message id (without angle brackets). */
    class Posted(val name: String, val data: ByteArray, val partSize: Int, val ids: List<String>, val sizes: List<Int>) {
        /** The id of the part holding byte [position] of the file. */
        fun idAt(position: Long): String = ids[(position / partSize).toInt()]

        /** The bytes of the file part [id] holds, as offsets in it. */
        fun bytesOf(id: String): LongRange = ids.indexOf(id).let { i -> i.toLong() * partSize until minOf((i + 1L) * partSize, data.size.toLong()) }
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val articles = ConcurrentHashMap<String, ByteArray>()

    /** Articles whose BODY answers 430; STAT still finds them. */
    val missing: MutableSet<String> = CopyOnWriteArraySet()

    /** How many times BODY asked for each article. */
    val bodies = ConcurrentHashMap<String, AtomicInteger>()

    /** Connections accepted so far. */
    val connections = AtomicInteger()

    /** Connections the idle rule ended ([idleAfterMs]): gone silent, or closed. */
    val silenced = AtomicInteger()

    /** Commands read and dropped on connections gone silent. */
    val dropped = AtomicInteger()

    /** The engine's provider for this server. */
    val provider: ProviderConfig get() = ProviderConfig("local", "local", "127.0.0.1", server.localPort, "", "", useTls = false, maxConnections = 4)

    init {
        thread(isDaemon = true, name = "local-nntp-accept") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                val index = connections.getAndIncrement()
                thread(isDaemon = true, name = "local-nntp-conn") { serve(socket, index) }
            }
        }
    }

    /**
     * Posts [data] as the file [name] in parts of [partSize], under ids tagged [tag] (a test's and its variant's: a part a
     * cache holds from one variant is never taken for another's).
     */
    fun post(name: String, data: ByteArray, partSize: Int, tag: String): Posted {
        val parts = YencEncoder.encodeMultiPart(data, name, partSize)
        val ids = parts.indices.map { "lnntp-$tag-${it + 1}@local" }
        parts.forEachIndexed { i, part -> articles[ids[i]] = part.data }
        return Posted(name, data, partSize, ids, parts.map { it.data.size })
    }

    /** The NZB of the files [posted] (one, or an archive's volumes in their order), as an indexer hands one over. */
    fun nzb(vararg posted: Posted): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n")
        posted.forEachIndexed { n, file ->
            append("<file poster=\"test@local\" date=\"1791985721\" subject=\"[${n + 1}/${posted.size}] - &quot;${file.name}&quot; yEnc (1/${file.ids.size})\">")
            append("<groups><group>alt.binaries.test</group></groups><segments>")
            file.ids.forEachIndexed { i, id -> append("<segment bytes=\"${file.sizes[i]}\" number=\"${i + 1}\">$id</segment>") }
            append("</segments></file>\n")
        }
        append("</nzb>\n")
    }.encodeToByteArray()

    override fun close() = server.close()

    private fun serve(socket: Socket, index: Int) = try {
        socket.use {
            val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val out = it.getOutputStream()
            var lastAnswerEnd = System.nanoTime()
            fun reply(line: String) {
                out.write("$line\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()
                lastAnswerEnd = System.nanoTime()
            }
            reply("200 local server ready")
            var silent = false
            for (command in generateSequence { runCatching { reader.readLine() }.getOrNull() }) {
                if (silent) {
                    dropped.incrementAndGet()
                    continue
                }
                if (diesAt(index, lastAnswerEnd)) {
                    silenced.incrementAndGet()
                    when (onIdle) {
                        OnIdle.SILENT -> {
                            silent = true
                            dropped.incrementAndGet()
                            continue
                        }
                        OnIdle.RST -> it.setSoLinger(true, 0)
                        OnIdle.FIN -> it.shutdownOutput()
                        OnIdle.KICK -> {
                            reply("400 idle timeout")
                            it.shutdownOutput()
                        }
                    }
                    break
                }
                when {
                    command.startsWith("BODY ") -> {
                        val sent = command.removePrefix("BODY ")
                        val id = sent.removePrefix("<").removeSuffix(">")
                        bodies.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                        val article = articles[id]?.takeIf { id !in missing }
                        if (article == null) {
                            reply("430 no such article")
                        } else {
                            out.write("222 0 $sent\r\n".toByteArray(Charsets.ISO_8859_1))
                            out.write(article)
                            reply(".")
                        }
                    }
                    command.startsWith("STAT ") -> {
                        val sent = command.removePrefix("STAT ")
                        reply(if (articles.containsKey(sent.removePrefix("<").removeSuffix(">"))) "223 0 $sent" else "430 no such article")
                    }
                    command.startsWith("AUTHINFO USER ") -> reply("381 password required")
                    command.startsWith("AUTHINFO PASS ") -> reply("281 authentication accepted")
                    command == "DATE" -> reply("111 20261015000000")
                    command == "QUIT" -> {
                        reply("205 bye")
                        break
                    }
                    else -> reply("500 unknown command")
                }
            }
        }
    } catch (_: IOException) {
        // The client went away.
    }

    /** Whether connection [index], whose last answer ended at [lastAnswerEnd], is dead for a command now. */
    private fun diesAt(index: Int, lastAnswerEnd: Long): Boolean {
        val after = idleAfterMs ?: return false
        return index % outOf < affected && System.nanoTime() - lastAnswerEnd >= after * 1_000_000
    }
}
