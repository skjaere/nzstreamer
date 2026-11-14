package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceListing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

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
}
