package io.skjaere.usenet.app.cast

import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import io.skjaere.usenet.app.casting.CastMetadata
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * L62: what a load asks of the device, the plain fields only: Google's metadata keeps its values in an android.os.Bundle,
 * which the JVM's stubs don't hold (CastItemsDeviceTest reads them on a phone). Each load has an id of its own, opaque, and
 * its token URL goes to the device alone, never in the session's item; another sender's media becomes an item of its id
 * alone. The address, the film and the device are invented.
 */
class CastItemsTest {
    private val url = "http://192.168.0.20:41234/0123456789abcdef0123456789abcdef/v.mkv"
    private val said = CastMetadata("Copper Crown", "2026", CastMetadata.Kind.Film, imageUrl = null)

    @Test
    fun `a load's item goes as its own id, the token URL, the container's type, buffered, with no custom data`() {
        val item = CastItems.item(url, "video/x-matroska", said, artwork = null, DEVICE, SDK)
        val sent = CastItems.toMediaQueueItem(item).media ?: throw AssertionError("no media")
        assertEquals(item.mediaId, sent.contentId)
        assertEquals(url, sent.contentUrl)
        assertEquals("video/x-matroska", sent.contentType)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, sent.streamType)
        assertNull(sent.customData)
    }

    /** The session's item never holds the token: Media3 publishes its id and metadata to other apps. */
    @Test
    fun `the id is opaque and new for each load, and the session's item has no address`() {
        val first = CastItems.item(url, "video/x-matroska", said, artwork = null, DEVICE, SDK)
        val again = CastItems.item(url, "video/x-matroska", said, artwork = null, DEVICE, SDK)
        assertNotEquals(first.mediaId, again.mediaId)
        listOf(first, again).forEach { item ->
            assertFalse("0123456789abcdef" in item.mediaId)
            assertFalse("v.mkv" in item.mediaId)
            assertNull(item.localConfiguration)
            assertNull(item.requestMetadata.mediaUri)
        }
    }

    @Test
    fun `the device's item of a load comes back as the load's own`() {
        val item = CastItems.item(url, "video/mp4", said, artwork = null, DEVICE, SDK)
        assertSame(item, CastItems.toMediaItem(CastItems.toMediaQueueItem(item)))
    }

    /**
     * W62, L120: the session's item, which Media3 hands back to the cast session for the notification and the lock screen,
     * has its line end on the device it plays on; what the device is told stays the play's own words (CastItemsDeviceTest
     * reads them, which the JVM's Bundle can't hold).
     */
    @Test
    fun `the session's item names the device on its line`() {
        val item = CastItems.item(url, "video/x-matroska", said, artwork = null, DEVICE, SDK)
        assertEquals("Copper Crown", item.mediaMetadata.title?.toString())
        assertEquals("2026 · on Kitchen display", item.mediaMetadata.artist?.toString())
        assertEquals("2026 · on Kitchen display", item.mediaMetadata.subtitle?.toString())
        val older = CastItems.item(url, "video/x-matroska", said, artwork = null, DEVICE, sdkInt = 28)
        assertEquals("On Kitchen display · 2026", older.mediaMetadata.artist?.toString())
    }

    /** Other apps share the Default Media Receiver (W32): their media is named by its id, and never throws. */
    @Test
    fun `another sender's media becomes an item of its id alone`() {
        val theirs = MediaQueueItem.Builder(MediaInfo.Builder("another-app-media").setContentUrl("http://192.168.0.30/theirs.mp4").build()).build()
        val item = CastItems.toMediaItem(theirs)
        assertEquals("another-app-media", item.mediaId)
        assertNull(item.localConfiguration)
    }

    private companion object {
        const val DEVICE = "Kitchen display"
        const val SDK = 34
    }
}
