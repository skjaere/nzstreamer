package io.skjaere.usenet.app.tv

import io.skjaere.usenet.app.link.PairedPhones
import io.skjaere.usenet.app.link.phoneClient
import io.skjaere.usenet.app.link.softwareKey
import io.skjaere.usenet.link.Connect
import io.skjaere.usenet.link.LinkClose
import io.skjaere.usenet.link.LinkHandler
import io.skjaere.usenet.link.LinkKey
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.PeerLink
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A phone paired on this TV as [name] (an invented one) and linked to it from the test's process, as the phone's app
 * links, to the TV's own address: the TV's [hello], what it asks the TV, the TV's answers, in order, and everything else
 * the TV says on it ([heard]). With [key], the phone paired before, linking again with its own key ([again]).
 */
class LinkedPhone(val name: String, private val pages: LinkPages, phones: PairedPhones, val key: LinkKey = softwareKey(), pair: Boolean = true) : Closeable {
    private val client = phoneClient(name, key)
    private val answers = LinkedBlockingQueue<LinkMessage>()

    /** What the TV said on the link but answers: its statuses and Finals, in order. */
    val heard = CopyOnWriteArrayList<LinkMessage>()
    val link: PeerLink

    /** The TV's hello as the link came up: its readiness (L25, L223) and the play now. */
    val hello: LinkMessage.Hello

    init {
        if (pair) phones.add(name, key.id)
        val handler = object : LinkHandler {
            override fun received(link: PeerLink, message: LinkMessage) {
                if (message is LinkMessage.Ack || message is LinkMessage.Rejected) answers.put(message) else heard += message
            }

            override fun closed(link: PeerLink, reason: LinkClose) = Unit
        }
        val connect = client.connect(pages.address(), pages.tvKey(), handler)
        val linked = connect as? Connect.Linked ?: throw AssertionError("not linked: $connect")
        link = linked.link
        hello = linked.hello
    }

    /** Sends [command] and returns the TV's answer to it. */
    fun ask(command: LinkMessage.Command): LinkMessage {
        link.send(command as LinkMessage)
        val answer = answers.poll(ANSWER_MS, TimeUnit.MILLISECONDS) ?: throw AssertionError("no answer to ${command::class.simpleName}")
        val seq = (answer as? LinkMessage.Ack)?.seq ?: (answer as LinkMessage.Rejected).seq
        if (seq != command.seq) throw AssertionError("the answer to ${command.seq} was for $seq")
        return answer
    }

    /** The session a Play's answer started; fails on a refusal. */
    fun session(answer: LinkMessage): String =
        (answer as? LinkMessage.Ack)?.session ?: throw AssertionError("refused: ${(answer as? LinkMessage.Rejected)?.reason}")

    /** The statuses the TV sent this phone, in order. */
    fun statuses(): List<LinkMessage.Status> = heard.filterIsInstance<LinkMessage.Status>()

    /** This phone's link again, a new one with the same key, as the phone's app links again coming back to the front; this one closed first. */
    fun again(phones: PairedPhones): LinkedPhone {
        close()
        return LinkedPhone(name, pages, phones, key, pair = false)
    }

    override fun close() = client.close()

    private companion object {
        const val ANSWER_MS = 10_000L
    }
}
