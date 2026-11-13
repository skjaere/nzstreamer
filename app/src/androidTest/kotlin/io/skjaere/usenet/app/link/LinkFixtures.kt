package io.skjaere.usenet.app.link

import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.data.PairedPhone
import io.skjaere.usenet.link.KeyId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlin.random.Random

/**
 * The TV's paired phones around a test: none as it starts ([clear]), and no recent Remove either (L4a), each case
 * pairing the invented ones it needs, and the rows and the Remove's time the device had put back after ([restore]),
 * those the test added gone. The link identity is made before any is added, as the listener makes it before any pairing.
 */
class PairedPhones {
    private val link = InstrumentationRegistry.getInstrumentation().targetContext.container.data.link
    private var before: List<PairedPhone> = emptyList()
    private var removedBefore: Long? = null

    fun clear() = runBlocking {
        // Made first, if this install has none yet: a new identity clears the pairings, the invented ones too.
        link.identity()
        before = link.phones.first()
        removedBefore = link.file.first().removedAt
        before.forEach { row -> row.key?.let { link.removePhone(it) } }
        // A Remove a test made earlier would keep the TV listening with no phone paired.
        link.setRemovedAt(null)
    }

    /** An invented phone paired on this TV, as a pairing would have saved it. */
    fun add(name: String = "Invented phone", key: KeyId = KeyId.of(Random.nextBytes(91))): KeyId = runBlocking {
        check(link.addPhone(key, name, "10.0.2.99"))
        key
    }

    fun restore() = runBlocking {
        link.phones.first().forEach { row -> row.key?.let { link.removePhone(it) } }
        before.forEach { row -> row.key?.let { link.addPhone(it, row.name, row.address.orEmpty()) } }
        link.setRemovedAt(removedBefore)
    }
}

/** Whether a TCP connection to [address]:[port] is taken within a second. */
fun accepts(address: Inet4Address, port: Int): Boolean = runCatching { Socket().use { it.connect(InetSocketAddress(address, port), 1_000) } }.isSuccess

/** This device's own addresses. */
fun ownAddresses() = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
