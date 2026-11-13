package io.skjaere.usenet.link

import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkMessage.PairCommit
import io.skjaere.usenet.link.LinkMessage.PairConfirm
import io.skjaere.usenet.link.LinkMessage.PairNonce
import io.skjaere.usenet.link.LinkMessage.PairResult
import io.skjaere.usenet.link.LinkMessage.PairReveal
import io.skjaere.usenet.link.LinkMessage.PairWait
import io.skjaere.usenet.link.LinkMessage.Refused
import io.skjaere.usenet.link.LinkTestPeers.linkKey
import io.skjaere.usenet.link.LinkTestPeers.loopback
import io.skjaere.usenet.link.LinkTestPeers.millisSince
import io.skjaere.usenet.link.PairAttempt.Result
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pairing by numbers, commit then reveal (L21, L22): the numbers themselves, fixed by known answers, then the ceremony
 * over real sockets on loopback between the real listener and the real client, and against peers driven by hand that
 * break its order. The names are invented.
 */
class PairingTest {
    private val tv = linkKey()
    private val phone = linkKey()
    private val opened = Opened()
    private val closers = mutableListOf<AutoCloseable>()
    private val events = LinkedBlockingQueue<LinkEvent>()
    private val page = Page()
    private var port = 0

    /** The TV's paired phones, as its store keeps them: the page's saves land here. */
    private val paired = ConcurrentHashMap.newKeySet<KeyId>()

    @AfterEach
    fun closeAll() {
        closers.forEach { it.close() }
        opened.close()
    }

    /** The Pair a phone page: what it saves, and what it hears, in order. */
    private inner class Page : PairingHost {
        val heard = LinkedBlockingQueue<PairingEvent>()
        val saved: MutableList<NewPhone> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var saving: (NewPhone) -> Boolean = { true }

        override fun save(phone: NewPhone): Boolean = saving(phone).also { ok ->
            if (ok) {
                saved += phone
                paired += phone.key
            }
        }

        override fun heard(event: PairingEvent) = heard.put(event)

        inline fun <reified T : PairingEvent> next(seconds: Long = 5): T {
            val got = assertNotNull(heard.poll(seconds, TimeUnit.SECONDS), "the page heard nothing")
            return got as? T ?: fail("the page heard $got, not ${T::class.simpleName}")
        }

        /** Nothing more within [millis]. */
        fun quiet(millis: Long = 300) {
            val got = heard.poll(millis, TimeUnit.MILLISECONDS)
            assertNull(got, "the page heard $got")
        }
    }

    /** A phone's user: when it was told to wait, and the numbers it was shown. */
    private class User : PairHandler {
        val waits = AtomicInteger()
        val checks = LinkedBlockingQueue<PairCheck>()

        override fun waiting() {
            waits.incrementAndGet()
        }

        override fun compare(check: PairCheck) = checks.put(check)

        fun check(): PairCheck = assertNotNull(checks.poll(5, TimeUnit.SECONDS), "no number shown")

        fun awaitWaiting() = LinkTestPeers.eventually("not told to wait") { waits.get() > 0 }
    }

    private fun server(limits: LinkLimits = LinkLimits(), open: Boolean = true): LinkServer {
        val server = LinkServer(
            bind = loopback as Inet4Address, key = tv, name = "Living room TV", app = "0.9.1",
            paired = { paired.toSet() }, pairedHello = { PairedHello(Hello.Ready(provider = true, indexer = true, tmdb = false)) },
            allowPeer = { it.isLoopbackAddress }, limits = limits, handler = Recorder(), onEvent = { events.put(it) },
        )
        closers += server
        port = server.start()
        if (open) server.openPairing(page)
        return server
    }

    private fun client(key: LinkKey = phone, name: String = "Galaxy phone", limits: LinkLimits = LinkLimits()) =
        LinkClient(key, name, "0.9.0", allowPeer = { it.isLoopbackAddress }, limits = limits).also { closers += it }

    private fun at(port: Int) = InetSocketAddress(loopback, port)

    /** A pairing run on a thread of its own, its user to answer and its result to come. */
    private fun attempt(client: LinkClient = client(), to: Int = port): Pair<User, CompletableFuture<Result>> {
        val user = User()
        val result = CompletableFuture<Result>()
        val attempt = client.pairing(at(to))
        thread(isDaemon = true) {
            try {
                result.complete(attempt.run(user))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return user to result
    }

    private fun CompletableFuture<Result>.result(): Result = get(5, TimeUnit.SECONDS)

    /** A phone driven by hand from [from]: TLS with [key] for pairing, the TV's hello read, its own sent with purpose pair. */
    private fun handPhone(key: LinkKey = linkKey(), from: String = "127.0.0.1"): Peer {
        val socket = opened.connect(port, from)
        val handshake = assertIs<Handshake.Accepted>(LinkTls.client(socket, key, PeerPolicy.Pairing))
        opened.add(handshake.socket)
        val peer = Peer(handshake.socket)
        assertEquals("Living room TV", assertIs<Hello>(peer.next()).name)
        peer.send(Hello(1, 1, Role.Phone, "Galaxy phone", app = "0.9.0", purpose = Purpose.Pair))
        return peer
    }

    /**
     * A TV that isn't NZStreamer's listener: TLS for pairing with [tv]'s key, its short hello, the phone's hello read,
     * then [script]. Returns its port and what the script returns.
     */
    private fun <T> fakeTv(script: (Peer) -> T): Pair<Int, CompletableFuture<T>> {
        val server = opened.add(ServerSocket(0, 8, loopback))
        val result = CompletableFuture<T>()
        thread(isDaemon = true) {
            try {
                val socket = opened.add(server.accept())
                socket.soTimeout = 5_000
                val handshake = LinkTls.server(socket, tv, PeerPolicy.Pairing) as Handshake.Accepted
                opened.add(handshake.socket)
                val peer = Peer(handshake.socket)
                peer.send(Hello(1, 1, Role.Tv, "Living room TV"))
                assertEquals(Purpose.Pair, assertIs<Hello>(peer.next()).purpose)
                result.complete(script(peer))
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return server.localPort to result
    }

    /** Whether [socket] gets nothing at all: no byte before the server closes it. */
    private fun unanswered(socket: Socket): Boolean = try {
        socket.getInputStream().read() < 0
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: IOException) {
        true
    }

    private val LinkKey.spki: ByteArray get() = certificate.publicKey.encoded

    // The numbers.

    @Test
    fun `known-answer vectors fix the commit and the six digits`() {
        val tvNonce = ByteArray(32) { it.toByte() }
        val phoneNonce = ByteArray(32) { (32 + it).toByte() }
        // Worked out apart from this code (Python's hashlib) from the keys and nonces as L21 lays them out.
        assertEquals("708a706396a737f135af5046214bad0a9f073bd65f57b17a77844f03138f5fc5", hex(Pairing.commit(TV_SPKI, PHONE_SPKI, tvNonce)))
        // The hash's first byte is 0xf8: read signed it would be negative.
        assertEquals("396576", Pairing.number(PHONE_SPKI, TV_SPKI, phoneNonce, tvNonce).digits)
        // 0x88: a signed remainder would be -172257.
        assertEquals("379359", Pairing.number(PHONE_SPKI, TV_SPKI, ByteArray(32), tvNonce).digits)
        // 0x03: the high bit clear.
        assertEquals("141828", Pairing.number(PHONE_SPKI, TV_SPKI, ByteArray(32) { 3 }, tvNonce).digits)
        // A leading zero is kept, and the digits are ASCII in any locale.
        val leadingZero = Pairing.number(PHONE_SPKI, TV_SPKI, ByteArray(32) { 2 }, tvNonce)
        assertEquals("078674", leadingZero.digits)
        val before = Locale.getDefault()
        try {
            val arabic = Locale.forLanguageTag("ar-EG")
            Locale.setDefault(arabic)
            assertNotEquals("078674", String.format(arabic, "%06d", 78_674), "this JVM's ar-EG has ASCII digits: the case proves nothing")
            assertEquals("078674", leadingZero.digits)
        } finally {
            Locale.setDefault(before)
        }
        // A number prints as its type alone.
        assertEquals("PairNumber", leadingZero.toString())
    }

    @Test
    fun `each of the four inputs changes the number, and each of the three the commit`() {
        val tvNonce = ByteArray(32) { it.toByte() }
        val phoneNonce = ByteArray(32) { (32 + it).toByte() }
        val number = Pairing.number(PHONE_SPKI, TV_SPKI, phoneNonce, tvNonce)
        val otherKey = linkKey().spki
        fun flipped(bytes: ByteArray, at: Int) = bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
        assertNotEquals(number, Pairing.number(otherKey, TV_SPKI, phoneNonce, tvNonce), "the phone's key")
        assertNotEquals(number, Pairing.number(PHONE_SPKI, otherKey, phoneNonce, tvNonce), "the TV's key")
        assertNotEquals(number, Pairing.number(PHONE_SPKI, TV_SPKI, flipped(phoneNonce, 31), tvNonce), "the phone's nonce")
        assertNotEquals(number, Pairing.number(PHONE_SPKI, TV_SPKI, phoneNonce, flipped(tvNonce, 0)), "the TV's nonce")
        // The keys' places count: the same two keys the other way round give another number.
        assertNotEquals(number, Pairing.number(TV_SPKI, PHONE_SPKI, phoneNonce, tvNonce), "keys swapped")
        val commit = Pairing.commit(TV_SPKI, PHONE_SPKI, tvNonce)
        assertFalse(commit.contentEquals(Pairing.commit(otherKey, PHONE_SPKI, tvNonce)), "the commit's TV key")
        assertFalse(commit.contentEquals(Pairing.commit(TV_SPKI, otherKey, tvNonce)), "the commit's phone key")
        assertFalse(commit.contentEquals(Pairing.commit(TV_SPKI, PHONE_SPKI, flipped(tvNonce, 5))), "the commit's nonce")
        assertFalse(commit.contentEquals(Pairing.commit(PHONE_SPKI, TV_SPKI, tvNonce)), "the commit's keys swapped")
    }

    // The ceremony.

    @Test
    fun `honest peers show the same six digits, and pair once both say yes`() {
        server()
        val (user, result) = attempt()
        val question = page.next<PairingEvent.Asking>().question
        val check = user.check()
        assertEquals(question.number, check.number)
        assertEquals(phone.id, question.phone)
        assertEquals("Galaxy phone", question.name)
        assertTrue(question.address.isLoopbackAddress)
        assertEquals("Living room TV", check.tvName)
        // Nothing printed holds the number or a name (L13).
        for (printed in listOf(question.toString(), check.toString(), PairingEvent.Asking(question).toString())) {
            assertFalse(question.number.digits in printed || "Galaxy" in printed || "Living" in printed, printed)
        }
        check.yes()
        question.ok()
        val pairedTv = assertIs<Result.Paired>(result.result())
        assertEquals(tv.id, pairedTv.tv)
        assertEquals("Living room TV", pairedTv.name)
        assertEquals(PairOutcome.Paired, page.next<PairingEvent.Ended>().outcome)
        val saved = page.saved.single()
        assertEquals(phone.id, saved.key)
        assertEquals("Galaxy phone", saved.name)
        // The pairing holds: the phone links with the TV's key pinned, and the TV knows the phone's.
        assertIs<Connect.Linked>(client().connect(at(port), pairedTv.tv, Recorder()))
    }

    @Test
    fun `a reveal that doesn't match the commit ends the ceremony, nothing saved`() {
        val (port, tvHeard) = fakeTv { peer ->
            peer.send(PairCommit(hex(Pairing.commit(tv.spki, phone.spki, ByteArray(32) { 1 }))))
            assertIs<PairNonce>(peer.next())
            peer.send(PairReveal(hex(ByteArray(32) { 2 })))
            // What the phone says then: nothing; it leaves.
            peer.next()
        }
        val (user, result) = attempt(to = port)
        assertEquals(Result.Stopped(PairOutcome.WrongCommit), result.result())
        assertNull(tvHeard.get(5, TimeUnit.SECONDS), "the phone went on after a wrong reveal")
        assertTrue(user.checks.isEmpty(), "a number was shown")
    }

    @Test
    fun `a second commit after the phone's nonce ends the ceremony and closes the connection`() {
        val (port, tvHeard) = fakeTv { peer ->
            peer.send(PairCommit(hex(Pairing.commit(tv.spki, phone.spki, ByteArray(32) { 1 }))))
            assertIs<PairNonce>(peer.next())
            peer.send(PairCommit(hex(Pairing.commit(tv.spki, phone.spki, ByteArray(32) { 3 }))))
            peer.next()
        }
        val (user, result) = attempt(to = port)
        assertEquals(Result.Stopped(PairOutcome.Broken), result.result())
        assertNull(tvHeard.get(5, TimeUnit.SECONDS), "the phone kept the connection")
        assertTrue(user.checks.isEmpty())
    }

    @Test
    fun `a reveal before the phone's nonce ends the ceremony and closes the connection`() {
        val (port, tvHeard) = fakeTv { peer ->
            peer.send(PairReveal(hex(ByteArray(32) { 1 })))
            peer.next()
        }
        val (user, result) = attempt(to = port)
        assertEquals(Result.Stopped(PairOutcome.Broken), result.result())
        assertNull(tvHeard.get(5, TimeUnit.SECONDS), "the phone sent its nonce, or kept the connection")
        assertTrue(user.checks.isEmpty())
    }

    @Test
    fun `a second nonce after the TV's reveal ends the ceremony, nothing saved, and closes the connection`() {
        server()
        val peer = handPhone()
        assertIs<PairCommit>(peer.next())
        peer.send(PairNonce(hex(ByteArray(32) { 4 })))
        assertIs<PairReveal>(peer.next())
        val question = page.next<PairingEvent.Asking>().question
        // The number is known now: a second nonce, to try for another, ends it.
        peer.send(PairNonce(hex(ByteArray(32) { 5 })))
        assertTrue(peer.endsWithin(2_000), "the connection stayed open")
        val ended = page.next<PairingEvent.Ended>()
        assertEquals(PairOutcome.Broken, ended.outcome)
        assertTrue(ended.question === question)
        // An OK now changes nothing.
        question.ok()
        page.quiet()
        assertTrue(page.saved.isEmpty())
    }

    @Test
    fun `a confirm before the TV's reveal ends the ceremony, nothing saved, and closes the connection`() {
        server()
        val peer = handPhone()
        assertIs<PairCommit>(peer.next())
        peer.send(PairConfirm(yes = true))
        assertTrue(peer.endsWithin(2_000), "the connection stayed open")
        val ended = page.next<PairingEvent.Ended>()
        assertEquals(PairOutcome.Broken, ended.outcome)
        assertNull(ended.question, "a question was asked")
        assertTrue(page.saved.isEmpty())
    }

    @Test
    fun `the TV saves only once its OK and the phone's yes are both in, and tells the phone after`() {
        server()
        for (okFirst in listOf(true, false)) {
            val key = linkKey()
            val peer = handPhone(key)
            assertIs<PairCommit>(peer.next())
            val nonce = ByteArray(32) { 7 }
            peer.send(PairNonce(hex(nonce)))
            val reveal = assertIs<PairReveal>(peer.next())
            val question = page.next<PairingEvent.Asking>().question
            // The TV asks with the number the phone works out from the two keys TLS met.
            assertEquals(Pairing.number(key.spki, tv.spki, nonce, unhex(reveal.n)), question.number)
            if (okFirst) question.ok() else peer.send(PairConfirm(yes = true))
            page.quiet()
            assertTrue(page.saved.isEmpty(), "saved with one yes (OK first: $okFirst)")
            val savedAt = AtomicLong()
            page.saving = {
                Thread.sleep(200)
                savedAt.set(System.nanoTime())
                true
            }
            if (okFirst) peer.send(PairConfirm(yes = true)) else question.ok()
            assertEquals(PairResult(ok = true), peer.next())
            val heardAt = System.nanoTime()
            assertTrue(savedAt.get() in 1 until heardAt, "the phone heard before the TV had saved")
            assertEquals(key.id, page.saved.single().key)
            assertEquals(PairOutcome.Paired, page.next<PairingEvent.Ended>().outcome)
            // The connection ends with it: one ceremony a connection.
            assertTrue(peer.endsWithin(2_000), "the connection stayed open")
            page.saved.clear()
            page.saving = { true }
        }
    }

    @Test
    fun `the phone counts itself paired only on the TV's word, after its own yes`() {
        // A TV that says it saved before the phone said yes.
        val (early, _) = fakeTv { peer ->
            val nonce = ByteArray(32) { 1 }
            peer.send(PairCommit(hex(Pairing.commit(tv.spki, phone.spki, nonce))))
            assertIs<PairNonce>(peer.next())
            peer.send(PairReveal(hex(nonce)))
            peer.send(PairResult(ok = true))
            peer.next()
        }
        val (user, result) = attempt(to = early)
        user.check()
        assertEquals(Result.Stopped(PairOutcome.Broken), result.result())

        // A TV that never says: the phone gives up once the TV's question would have closed.
        val limits = LinkLimits(questionMillis = 300, helloMillis = 500)
        val (silent, _) = fakeTv { peer ->
            val nonce = ByteArray(32) { 1 }
            peer.send(PairCommit(hex(Pairing.commit(tv.spki, phone.spki, nonce))))
            assertIs<PairNonce>(peer.next())
            peer.send(PairReveal(hex(nonce)))
            assertEquals(PairConfirm(yes = true), peer.next())
            peer.next()
        }
        val (quiet, waiting) = attempt(client(limits = limits), silent)
        quiet.check().yes()
        val started = System.nanoTime()
        assertEquals(Result.Stopped(PairOutcome.TimedOut), waiting.result())
        assertTrue(millisSince(started) < 3_000, "the phone waited ${millisSince(started)} ms")
    }

    @Test
    fun `No on the phone, or Cancel on the TV, ends the ceremony on both, nothing saved`() {
        server()
        val (user, result) = attempt()
        page.next<PairingEvent.Asking>()
        user.check().no()
        assertEquals(Result.Stopped(PairOutcome.No), result.result())
        assertEquals(PairOutcome.No, page.next<PairingEvent.Ended>().outcome)

        val (other, cancelled) = attempt()
        val question = page.next<PairingEvent.Asking>().question
        other.check()
        question.cancel()
        assertEquals(Result.Stopped(PairOutcome.Declined), cancelled.result())
        assertEquals(PairOutcome.Declined, page.next<PairingEvent.Ended>().outcome)
        assertTrue(page.saved.isEmpty())
    }

    @Test
    fun `a pairing the TV can't save ends on both ends, nothing saved`() {
        server()
        page.saving = { false }
        val (user, result) = attempt()
        val question = page.next<PairingEvent.Asking>().question
        user.check().yes()
        question.ok()
        assertEquals(Result.Stopped(PairOutcome.NotSaved), result.result())
        assertEquals(PairOutcome.NotSaved, page.next<PairingEvent.Ended>().outcome)
        assertTrue(paired.isEmpty())
    }

    @Test
    fun `the TV's question closes by itself, and so does a ceremony whose nonce never comes`() {
        server(LinkLimits(questionMillis = 400, helloMillis = 400, pairingPauseMillis = 0))
        val (user, result) = attempt()
        val asked = System.nanoTime()
        page.next<PairingEvent.Asking>()
        user.check().yes()
        assertEquals(Result.Stopped(PairOutcome.TimedOut), result.result())
        assertEquals(PairOutcome.TimedOut, page.next<PairingEvent.Ended>().outcome)
        assertTrue(millisSince(asked) >= 350, "closed after ${millisSince(asked)} ms")

        val peer = handPhone()
        assertIs<PairCommit>(peer.next())
        assertEquals(PairResult(ok = false, reason = PairEnd.TimedOut), peer.next())
        assertTrue(peer.endsWithin(2_000), "the connection stayed open")
        val ended = page.next<PairingEvent.Ended>()
        assertEquals(PairOutcome.TimedOut, ended.outcome)
        assertNull(ended.question)
        assertTrue(page.saved.isEmpty())
    }

    @Test
    fun `a TV not taking pairings refuses, and pairing closes by itself after its time`() {
        val server = server(LinkLimits(pairingMillis = 1_000), open = false)
        assertEquals(Result.Refused(Refusal.NotPairing), attempt().second.result())

        server.openPairing(page)
        val opened = System.nanoTime()
        assertEquals(PairingEvent.Closed, page.next<PairingEvent.Closed>())
        assertTrue(millisSince(opened) >= 950, "closed after ${millisSince(opened)} ms")
        assertEquals(Result.Refused(Refusal.NotPairing), attempt().second.result())

        // Opened again: a ceremony that ends starts its time again.
        server.openPairing(page)
        val reopened = System.nanoTime()
        Thread.sleep(600)
        val (user, result) = attempt()
        val question = page.next<PairingEvent.Asking>().question
        user.check().yes()
        question.ok()
        assertIs<Result.Paired>(result.result())
        assertEquals(PairOutcome.Paired, page.next<PairingEvent.Ended>().outcome)
        val ended = System.nanoTime()
        page.next<PairingEvent.Closed>()
        assertTrue(millisSince(ended) >= 950 && millisSince(reopened) >= 1_500, "closed ${millisSince(ended)} ms after the ceremony ended")
    }

    @Test
    fun `closing pairing ends a ceremony under way, nothing saved, and the page hears no more`() {
        val server = server()
        val (user, result) = attempt()
        val question = page.next<PairingEvent.Asking>().question
        user.check().yes()
        server.closePairing()
        assertEquals(Result.Stopped(PairOutcome.Closed), result.result())
        question.ok()
        page.quiet()
        assertTrue(page.saved.isEmpty())
        assertEquals(Result.Refused(Refusal.NotPairing), attempt().second.result())
    }

    @Test
    fun `one ceremony runs, one phone waits and is told so, a third is refused busy`() {
        server()
        val (first, firstResult) = attempt()
        val firstQuestion = page.next<PairingEvent.Asking>().question
        first.check()
        val second = linkKey()
        val (waiting, secondResult) = attempt(client(second, "Pixel phone"))
        waiting.awaitWaiting()
        assertEquals(PairingEvent.Waiting(another = true), page.next<PairingEvent.Waiting>())
        val (_, thirdResult) = attempt(client(linkKey(), "Tablet"))
        assertEquals(Result.Refused(Refusal.Busy), thirdResult.result())

        // The first ends: the second's turn comes, with a ceremony of its own.
        firstQuestion.cancel()
        assertEquals(Result.Stopped(PairOutcome.Declined), firstResult.result())
        assertEquals(PairOutcome.Declined, page.next<PairingEvent.Ended>().outcome)
        assertEquals(PairingEvent.Waiting(another = false), page.next<PairingEvent.Waiting>())
        val secondQuestion = page.next<PairingEvent.Asking>().question
        assertEquals(second.id, secondQuestion.phone)
        assertEquals("Pixel phone", secondQuestion.name)
        val check = waiting.check()
        assertEquals(secondQuestion.number, check.number)
        assertNotEquals(firstQuestion.number, check.number)
        check.yes()
        secondQuestion.ok()
        assertEquals(tv.id, assertIs<Result.Paired>(secondResult.result()).tv)
        assertEquals(second.id, page.saved.single().key)
    }

    @Test
    fun `three failed ceremonies from one address refuse it, and another address can still pair`() {
        // The count by address alone: no wait after each failure.
        val server = server(LinkLimits(pairingPauseMillis = 0))
        repeat(3) {
            val peer = handPhone(from = "127.0.0.2")
            assertIs<PairCommit>(peer.next())
            peer.send(PairNonce(hex(ByteArray(32))))
            assertIs<PairReveal>(peer.next())
            page.next<PairingEvent.Asking>()
            peer.send(PairConfirm(yes = false))
            assertEquals(PairOutcome.No, page.next<PairingEvent.Ended>().outcome)
            assertTrue(peer.endsWithin(2_000), "the connection stayed open")
        }
        val fourth = handPhone(from = "127.0.0.2")
        assertEquals(Refused(Refusal.TooMany), fourth.next())
        assertTrue(fourth.endsWithin(2_000), "the connection stayed open")
        assertEquals(LinkEvent.Refused(Refusal.TooMany), generateSequence { events.poll(5, TimeUnit.SECONDS) }.first { it is LinkEvent.Refused })

        // Another address pairs.
        val (user, result) = attempt()
        val question = page.next<PairingEvent.Asking>().question
        user.check().yes()
        question.ok()
        assertIs<Result.Paired>(result.result())

        // Pair a phone opened again forgets the failures.
        server.openPairing(page)
        assertIs<PairCommit>(handPhone(from = "127.0.0.2").next())
    }

    /**
     * A relay with many addresses (L21): each failed ceremony, from whatever address, makes the next commit wait, so the
     * tries against one number a phone shows are few. The phone whose turn it is meanwhile is told to wait.
     */
    @Test
    fun `after a failed ceremony from any address the next commit waits, its phone told to wait`() {
        server()
        val relay = handPhone(from = "127.0.0.2")
        assertIs<PairCommit>(relay.next())
        relay.send(PairConfirm(yes = true))
        assertEquals(PairOutcome.Broken, page.next<PairingEvent.Ended>().outcome)
        val ended = System.nanoTime()
        val next = handPhone(from = "127.0.0.3")
        assertEquals(PairWait, next.next(), "the next ceremony started at once")
        assertIs<PairCommit>(next.next())
        assertTrue(millisSince(ended) >= 1_900, "its commit came ${millisSince(ended)} ms after the failure")
    }

    @Test
    fun `each failure in a row doubles the wait, up to 16 times, and a pairing ends it`() {
        server(LinkLimits(pairingPauseMillis = 100))
        val waits = mutableListOf<Long>()
        var ended = 0L
        for (i in 0 until 7) {
            // Each from an address of its own, as a relay with many would.
            val relay = handPhone(from = "127.0.1.${i + 1}")
            val first = relay.next()
            assertIs<PairCommit>(if (first == PairWait) relay.next() else first)
            if (i > 0) waits += millisSince(ended)
            relay.send(PairConfirm(yes = true))
            assertEquals(PairOutcome.Broken, page.next<PairingEvent.Ended>().outcome)
            ended = System.nanoTime()
        }
        // 100, 200, 400, 800 and 1,600 ms, then 1,600 again.
        for ((i, wait) in waits.withIndex()) {
            val least = 100L shl i.coerceAtMost(4)
            assertTrue(wait in least - 20 until least + 1_000, "wait ${i + 1}: $wait ms, not about $least")
        }

        // A phone pairs after its own wait; the next commit then goes out at once.
        val (user, result) = attempt()
        user.awaitWaiting()
        val question = page.next<PairingEvent.Asking>().question
        user.check().yes()
        question.ok()
        assertIs<Result.Paired>(result.result())
        assertIs<PairCommit>(handPhone(from = "127.0.2.1").next())
    }

    @Test
    fun `four links, a pairing and a waiting phone fill the TV, and a seventh connection is closed`() {
        val users = List(4) { linkKey() }
        paired += users.map { it.id }
        server()
        for (key in users) assertIs<Connect.Linked>(client(key).connect(at(port), tv.id, Recorder()))
        attempt()
        page.next<PairingEvent.Asking>()
        val (waiting, _) = attempt(client(linkKey()))
        waiting.awaitWaiting()
        val seventh = opened.connect(port)
        assertTrue(unanswered(seventh), "the seventh connection was answered")
        assertEquals(LinkEvent.Dropped(Drop.Full), generateSequence { events.poll(5, TimeUnit.SECONDS) }.first { it is LinkEvent.Dropped })
    }

    private companion object {
        /** Two P-256 SubjectPublicKeyInfos, made once with openssl for these vectors: they guard nothing. */
        val TV_SPKI: ByteArray = Base64.getDecoder().decode(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0U+2D+z3w9W98HcVVYj5x3JACnayYHxh+CefaOJU3DREhNihSdCV1lLEMtNrW5f/5YvvrTWeZWlaP4ntQ9dMaw==",
        )
        val PHONE_SPKI: ByteArray = Base64.getDecoder().decode(
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEWZ7jUERsOWi4Oz/0qBaFDw3nK9LfP2WH1LAmW3NyUOz2x5Eynj0dDavHDaCuHi1+9T65u+accnfMebbAFjfaQA==",
        )

        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
