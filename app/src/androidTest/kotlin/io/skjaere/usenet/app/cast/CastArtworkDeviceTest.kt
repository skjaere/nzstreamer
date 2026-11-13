package io.skjaere.usenet.app.cast

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutionException

/**
 * L58, K1: the cast session's own artwork, with a real BitmapFactory. A still prepared before its item reaches the session
 * is handed over done, by its bytes or from the item's metadata, so the notification made for a new still never waits on
 * a decode (Media3's own loader decodes on an executor, and its late callback lets the foreground go in a long pause).
 * Bytes not prepared have nothing, and no still is ever loaded from an address. The stills are one colour, invented.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class CastArtworkDeviceTest {
    /** A still of one colour, as a TMDB still would come: PNG bytes, 320 by 180. */
    private fun still(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun aPreparedStillIsHandedOverDoneByItsBytesAndFromTheMetadata() {
        val artwork = CastArtwork()
        val bytes = still(Color.DKGRAY)
        artwork.prepare(bytes)
        val decoded = artwork.decodeBitmap(bytes)
        assertTrue("done at once", decoded.isDone)
        val bitmap = decoded.get()
        assertEquals(320, bitmap.width)
        assertEquals(180, bitmap.height)
        val fromMetadata = artwork.loadBitmapFromMetadata(MediaMetadata.Builder().setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build())
            ?: throw AssertionError("nothing for the item's still")
        assertTrue("done at once", fromMetadata.isDone)
        assertSame("the still prepared", bitmap, fromMetadata.get())
    }

    @Test
    fun bytesNotPreparedHaveNothingAndNoAddressIsLoaded() {
        val artwork = CastArtwork()
        artwork.prepare(still(Color.DKGRAY))
        val other = still(Color.GRAY)
        val decoded = artwork.decodeBitmap(other)
        assertTrue("answered at once", decoded.isDone)
        assertFailed(decoded)
        assertNull(artwork.loadBitmapFromMetadata(MediaMetadata.Builder().setArtworkData(other, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()))
        assertNull("no still, nothing", artwork.loadBitmapFromMetadata(MediaMetadata.Builder().setTitle("Harbor Lights").build()))
        val loaded = artwork.loadBitmap(Uri.parse("https://image.tmdb.org/t/p/w300/still-hl-s02e04.jpg"))
        assertTrue("refused at once", loaded.isDone)
        assertFailed(loaded)
    }

    /** The play's still and the one before it are kept, as a metadata change brings a new one; older ones go. */
    @Test
    fun theLastTwoStillsAreKept() {
        val artwork = CastArtwork()
        val (first, second, third) = listOf(Color.DKGRAY, Color.GRAY, Color.LTGRAY).map(::still)
        listOf(first, second, third).forEach(artwork::prepare)
        assertTrue("the one before kept, at once", artwork.decodeBitmap(second).isDone)
        assertTrue("the last kept, at once", artwork.decodeBitmap(third).isDone)
        assertFailed(artwork.decodeBitmap(first))
    }

    private fun assertFailed(future: ListenableFuture<Bitmap>) {
        try {
            future.get()
            throw AssertionError("a still where there is none")
        } catch (e: ExecutionException) {
            // None: as it should be.
        }
    }
}
