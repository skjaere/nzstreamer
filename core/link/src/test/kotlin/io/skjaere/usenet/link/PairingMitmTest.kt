package io.skjaere.usenet.link

import io.skjaere.usenet.link.LinkMessage.PairCommit
import io.skjaere.usenet.link.LinkMessage.PairConfirm
import io.skjaere.usenet.link.LinkMessage.PairNonce
import io.skjaere.usenet.link.LinkMessage.PairReveal
import io.skjaere.usenet.link.LinkTestPeers.linkKey
import io.skjaere.usenet.link.Pairing.Step
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A relay between a phone and a TV (L21): it presents a key of its own on each leg, the phone's to the TV and the
 * TV's to the phone, and wins only if both screens show the same number. Each honest end's last random input comes
 * after the relay is committed, so it can't steer either number: each try wins with probability 10⁻⁶, and the
 * one-ceremony rule keeps it so. Seeded, so a run is repeatable. The ceremony over sockets, where a broken order closes
 * the connection, is PairingTest's.
 */
class PairingMitmTest {
    private val tvKey = linkKey().spki
    private val phoneKey = linkKey().spki
    /** The relay's key on the TV's leg, as a phone, and on the phone's leg, as a TV. */
    private val relayAsPhone = linkKey().spki
    private val relayAsTv = linkKey().spki

    private val LinkKey.spki: ByteArray get() = certificate.publicKey.encoded

    private fun Random.nonce() = ByteArray(32).also(::nextBytes)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** The TV's leg to its number: its commit, the relay's [nonce] for it, its reveal. */
    private fun tvLeg(tv: Pairing.Tv, nonce: ByteArray): PairNumber {
        assertIs<PairCommit>(assertIs<Step.Send>(tv.commit()).said)
        return assertIs<Step.Show>(tv.received(PairNonce(hex(nonce)))).number
    }

    /** The phone's leg to its number: the relay commits to [nonce], hears the phone's, then reveals it. */
    private fun phoneLeg(phone: Pairing.Phone, nonce: ByteArray): PairNumber {
        val nonceSent = assertIs<Step.Send>(phone.received(PairCommit(hex(Pairing.commit(relayAsTv, phoneKey, nonce)))))
        assertIs<PairNonce>(nonceSent.said)
        return assertIs<Step.Show>(phone.received(PairReveal(hex(nonce)))).number
    }

    @Test
    fun `the relay must commit before it learns the phone's nonce, and over 2,000 tries its numbers never match`() {
        // The order is enforced: a phone sends its nonce only for a commit, and takes no reveal before it; a TV takes
        // no nonce before its commit.
        val random = Random(20_261_101)
        val early = Pairing.Phone(phoneKey, relayAsTv, random)
        assertEquals(PairOutcome.Broken, assertIs<Step.Ended>(early.received(PairReveal(hex(random.nonce())))).outcome)
        val unasked = Pairing.Tv(tvKey, relayAsPhone, random)
        assertEquals(PairOutcome.Broken, assertIs<Step.Ended>(unasked.received(PairNonce(hex(random.nonce())))).outcome)

        var matches = 0
        repeat(2_000) { i ->
            val tv = Pairing.Tv(tvKey, relayAsPhone, random)
            val phone = Pairing.Phone(phoneKey, relayAsTv, random)
            val (tvNumber, phoneNumber) = if (i % 2 == 0) {
                // The TV's leg first: the relay knows the TV's number, then commits to the phone, blind to its nonce.
                val tvNumber = tvLeg(tv, random.nonce())
                tvNumber to phoneLeg(phone, random.nonce())
            } else {
                // The phone's leg first: its number is fixed by the relay's commit; then the TV's nonce is hidden from it.
                val phoneNumber = phoneLeg(phone, random.nonce())
                tvLeg(tv, random.nonce()) to phoneNumber
            }
            if (tvNumber == phoneNumber) matches++
        }
        assertEquals(0, matches)
    }

    @Test
    fun `a relay that grinds 100,000 nonces of its own before committing does no better`() {
        val random = Random(77_310)
        var matches = 0
        repeat(5) {
            val tv = Pairing.Tv(tvKey, relayAsPhone, random)
            val phone = Pairing.Phone(phoneKey, relayAsTv, random)
            val tvNumber = tvLeg(tv, random.nonce())
            // The best it can grind for: a nonce whose number meets the TV's for a phone nonce it guesses.
            val guess = random.nonce()
            var chosen = random.nonce()
            for (n in 0 until 100_000) {
                val candidate = random.nonce()
                if (Pairing.number(phoneKey, relayAsTv, guess, candidate) == tvNumber) {
                    chosen = candidate
                    break
                }
            }
            if (phoneLeg(phone, chosen) == tvNumber) matches++
        }
        assertEquals(0, matches)
    }

    @Test
    fun `a relay that commits again, or sends its nonce again once it knows a number, is closed and never gets a match`() {
        val random = Random(4_101)
        repeat(200) {
            // The phone's leg: a second commit after the phone's nonce ends it, and no number shows.
            val phone = Pairing.Phone(phoneKey, relayAsTv, random)
            val first = random.nonce()
            assertIs<Step.Send>(phone.received(PairCommit(hex(Pairing.commit(relayAsTv, phoneKey, first)))))
            val second = random.nonce()
            assertEquals(PairOutcome.Broken, assertIs<Step.Ended>(phone.received(PairCommit(hex(Pairing.commit(relayAsTv, phoneKey, second))))).outcome)
            assertEquals(Step.Wait, phone.received(PairReveal(hex(second))))
            assertEquals(Step.Wait, phone.yes())
            assertEquals(PairOutcome.Broken, phone.outcome)

            // The TV's leg: once its number is out, a second nonce ends it, and nothing confirms it after.
            val tv = Pairing.Tv(tvKey, relayAsPhone, random)
            tvLeg(tv, random.nonce())
            assertEquals(PairOutcome.Broken, assertIs<Step.Ended>(tv.received(PairNonce(hex(random.nonce())))).outcome)
            assertEquals(Step.Wait, tv.ok())
            assertEquals(Step.Wait, tv.received(PairConfirm(yes = true)))
        }
    }
}
