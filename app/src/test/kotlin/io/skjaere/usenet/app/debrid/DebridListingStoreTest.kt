package io.skjaere.usenet.app.debrid

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.debrid.premiumize.PremiumizeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DebridListingStoreTest {
    @TempDir
    lateinit var dir: File

    private val file by lazy { File(dir, "debrid/listings.json") }

    private val listing = ServiceListing(listOf(ServiceFile(0, "Lantern.Bay.2031.2160p.mkv", 9_000, "f0")))

    /** Release [n] as a play prepares it from the account "tb", put as its attempt puts it; the id it is kept as. */
    private suspend fun DebridListingStore.prepared(n: Int): String =
        put("tb", "Lantern Bay $n", Prepared(InfoHash.of("%040x".format(n + 1)), listing, listing.files.single(), "t$n")).id

    /**
     * Each attempt of a play puts its release's listing as it is prepared, and its probe keeps it, whether it plays or not:
     * a play's ten failed attempts leave the ten listings played before kept, for Resume and the next episode, in memory
     * and in the file.
     */
    @Test
    fun `a play's failed attempts leave the listings played before kept`() = runBlocking<Unit> {
        val store = DebridListingStore(file, io = Dispatchers.Unconfined)
        val played = (0 until 10).map { n -> store.prepared(n).also { store.keep(it) } }
        (10 until 20).forEach { n -> store.keep(store.prepared(n)) }
        assertEquals(played, played.filter { store.get(it) != null })
        val again = DebridListingStore(file, io = Dispatchers.Unconfined)
        assertEquals(played, played.filter { again.get(it) != null })
    }

    /**
     * L420: a download's own copy of its torrent's listing is the entry as JSON, the account's id, the release's name and
     * what the service prepared (the hash, the files with the service's ids, the handle), and reads back as the same entry.
     * Never a link, though Premiumize's listing came with its files' links, nor the account's key.
     */
    @Test
    fun `an entry round-trips through a download's copy, with no link or key in it`() = runBlocking<Unit> {
        val hash = InfoHash.of("5eedc0de5eedc0de5eedc0de5eedc0de5eedc0de")
        val torbox = Prepared(
            hash,
            ServiceListing(listOf(ServiceFile(0, "$PACK/$EPISODE", 1_300_000_000, "3"), ServiceFile(1, "$PACK/Quiet.Meridian.S01E03.1080p.WEB.h264-WREN.mkv", 1_250_000_000, "4"))),
            ServiceFile(0, "$PACK/$EPISODE", 1_300_000_000, "3"),
            handle = "5021337",
        )
        val entry = DebridListingStore.Entry(DebridListingStore.idFor(ACCOUNT, hash), ACCOUNT, "Quiet Meridian S01E02", torbox)
        assertEquals(entry, DebridListingStore.entryOf(DebridListingStore.copyOf(entry)))

        // Premiumize's prepare holds its files' links, handed out once with no call: none of them goes in the copy.
        val link = "https://cdn.invented.example/dl/7f3a9c0b/$EPISODE"
        val premiumize = PremiumizeService(
            KEY,
            HttpClient(
                MockEngine {
                    respond(
                        """{"status":"success","content":[{"path":"$PACK/$EPISODE","size":1300000000,"link":"$link"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            ),
            baseUrl = "https://premiumize.invented.example/api",
            log = {},
        )
        val prepared = premiumize.prepare(hash) { it.files.single() }
        val held = DebridListingStore.Entry(DebridListingStore.idFor(ACCOUNT, hash), ACCOUNT, "Quiet Meridian S01E02", prepared)
        val copy = DebridListingStore.copyOf(held)
        assertEquals(held, DebridListingStore.entryOf(copy))
        listOf(link, "7f3a9c0b", "://", KEY).forEach { assertFalse(it in copy, "$it in the copy: $copy") }
        assertEquals(link, premiumize.link(prepared, prepared.picked!!).url, "the listing held its link all the same")
    }

    private companion object {
        /** An invented account's id and key, and a season pack's folder and an episode in it. */
        const val ACCOUNT = "0f1e2d3c-4b5a-4968-8776-a5b4c3d2e1f0"
        const val KEY = "pm-invented-key-0042"
        const val PACK = "Quiet.Meridian.S01.1080p.WEB.h264-WREN"
        const val EPISODE = "Quiet.Meridian.S01E02.1080p.WEB.h264-WREN.mkv"
    }
}
