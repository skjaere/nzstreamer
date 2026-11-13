package io.skjaere.usenet.core

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread
import kotlin.test.assertTrue

/**
 * A minimal NNTP server on loopback, for exercising real connects: greets with 200 (or stays
 * silent when [greet] is false), takes any AUTHINFO USER and accepts only [password], answers
 * STAT with 223 for the articles [has] (by default none) and 430 for the rest, BODY as [body]
 * says (by default 430) and QUIT with 205. Message ids are passed on as sent, angle brackets
 * included. Records the commands it receives. While [refusing], STAT and BODY get 502. Plain TCP,
 * or TLS 1.2 (what Ktor's client speaks) with [tls]'s certificate.
 *
 * Its connections can die after sitting idle, as a NAT, a VPN or a provider ends a flow nobody used
 * for a while (the libraries' fakes do the same). Each connection notes when its last answer ended
 * (the greeting's, at first). A command that comes once the connection has been idle [idleAfterMs]
 * or longer finds it dead for good, in the way [onIdle] says. Any answer keeps a connection alive, a
 * DATE's as any other, as a keepalive refreshes a NAT's entry. Null [idleAfterMs]: none die. Only
 * [affected] of every [outOf] connections can die, by accept order from 0 (K of M): connection i when
 * `i % outOf < affected`. On loopback, as through a NAT that dropped the flow, a silent
 * connection's writes are still taken: the client sees no answer, not an error.
 */
class FakeNntpServer(
    private val password: String = "secret",
    private val greet: Boolean = true,
    private val has: (id: String) -> Boolean = { false },
    private val body: (id: String) -> Body = { Body.Missing },
    tls: SSLContext? = null,
    private val idleAfterMs: Long? = null,
    private val onIdle: OnIdle = OnIdle.SILENT,
    private val affected: Int = 1,
    private val outOf: Int = 1,
) : AutoCloseable {
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

    /** An answer to BODY. [body] may block (on a latch, say), which holds back that connection. */
    sealed interface Body {
        /** 222 and [article], a yEnc-encoded article, then the terminating dot. */
        class Article(val article: ByteArray) : Body

        /** 430. */
        data object Missing : Body

        /** 222 and the first [bytes] of [article], then the connection drops. */
        class Dropped(val article: ByteArray, val bytes: Int) : Body
    }

    private val server = tls?.let { context ->
        (context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket)
            .apply { enabledProtocols = arrayOf("TLSv1.2") }
    } ?: ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val disconnected = CountDownLatch(1)
    private val received = CopyOnWriteArrayList<String>()
    /** The commands answered, over all connections (not those a dead connection dropped). */
    val commands: List<String> get() = received

    /** Connections accepted so far. */
    val connections = AtomicInteger()

    /** Connections the idle rule ended ([idleAfterMs]): gone silent, or closed. */
    val silenced = AtomicInteger()

    /** Commands read and dropped on connections gone silent. */
    val dropped = AtomicInteger()

    /** Like an account that ran out: logins still work, articles are refused. */
    @Volatile
    var refusing = false

    /** While set, AUTHINFO PASS gets this answer whatever the password, e.g. "502 Too many connections". */
    @Volatile
    var loginAnswer: String? = null

    init {
        thread(isDaemon = true, name = "fake-nntp-accept") {
            generateSequence { runCatching { server.accept() }.getOrNull() }.forEach { socket ->
                val index = connections.getAndIncrement()
                thread(isDaemon = true, name = "fake-nntp-conn") { serve(socket, index) }
            }
        }
    }

    fun provider(password: String = this.password) =
        ProviderConfig("fake", "fake", server.inetAddress.hostAddress, server.localPort, "user", password, useTls = false)

    /** Waits for the first client connection to end, i.e. for the client to hang up. */
    fun awaitDisconnect() = assertTrue(disconnected.await(5, TimeUnit.SECONDS), "client never closed the connection")

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
            if (greet) reply("200 fake server ready")
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
                received += command
                if (refusing && (command.startsWith("STAT ") || command.startsWith("BODY "))) {
                    reply("502 access denied")
                    continue
                }
                if (!command.startsWith("BODY ")) {
                    reply(answer(command))
                    continue
                }
                val id = command.removePrefix("BODY ")
                when (val answer = body(id)) {
                    Body.Missing -> reply("430 no such article")
                    is Body.Article -> {
                        out.write("222 0 $id\r\n".toByteArray(Charsets.ISO_8859_1))
                        out.write(answer.article)
                        reply(".")
                    }
                    is Body.Dropped -> {
                        out.write("222 0 $id\r\n".toByteArray(Charsets.ISO_8859_1))
                        out.write(answer.article, 0, answer.bytes)
                        out.flush()
                        break
                    }
                }
            }
        }
    } catch (e: IOException) {
        // The client went away, or refused a TLS handshake.
    } finally {
        disconnected.countDown()
    }

    /** Whether connection [index], whose last answer ended at [lastAnswerEnd], is dead for a command now. */
    private fun diesAt(index: Int, lastAnswerEnd: Long): Boolean {
        val after = idleAfterMs ?: return false
        return index % outOf < affected && System.nanoTime() - lastAnswerEnd >= after * 1_000_000
    }

    private fun answer(command: String) = when {
        command.startsWith("AUTHINFO USER ") -> "381 password required"
        command.startsWith("AUTHINFO PASS ") ->
            loginAnswer ?: if (command == "AUTHINFO PASS $password") "281 authentication accepted" else "481 authentication rejected"
        command.startsWith("STAT ") -> command.removePrefix("STAT ").let { id -> if (has(id)) "223 0 $id" else "430 no such article" }
        command == "DATE" -> "111 20261015000000"
        command == "QUIT" -> "205 bye"
        else -> "500 unknown command"
    }
}
