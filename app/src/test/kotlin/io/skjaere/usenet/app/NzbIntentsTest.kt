package io.skjaere.usenet.app

import android.content.Intent
import org.junit.jupiter.api.Test
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which intents of other apps open a .nzb here, and the title the file gets. */
class NzbIntentsTest {
    private val document = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FLantern%20Field.nzb"
    private val download = "content://media/external/downloads/1042"
    private val file = "file:///storage/emulated/0/Download/Lantern%20Field.nzb"

    @Test
    fun `a viewed nzb is opened by its content or file uri`() {
        assertEquals(document, NzbIntents.uri(Intent.ACTION_VIEW, data = document, stream = null))
        assertEquals(download, NzbIntents.uri(Intent.ACTION_VIEW, data = download, stream = null))
        assertEquals(file, NzbIntents.uri(Intent.ACTION_VIEW, data = file, stream = null))
        assertEquals("CONTENT://media/external/downloads/7", NzbIntents.uri(Intent.ACTION_VIEW, data = "CONTENT://media/external/downloads/7", stream = null))
    }

    @Test
    fun `a shared nzb is opened by its stream, not its data`() {
        assertEquals(download, NzbIntents.uri(Intent.ACTION_SEND, data = null, stream = download))
        assertEquals(download, NzbIntents.uri(Intent.ACTION_SEND, data = "content://elsewhere/1", stream = download))
        assertNull(NzbIntents.uri(Intent.ACTION_SEND, data = download, stream = null))
    }

    @Test
    fun `links and other actions are not taken`() {
        assertNull(NzbIntents.uri(Intent.ACTION_VIEW, data = "https://indexer.example/getnzb/1.nzb", stream = null))
        assertNull(NzbIntents.uri(Intent.ACTION_VIEW, data = "http://indexer.example/getnzb/1.nzb", stream = null))
        assertNull(NzbIntents.uri(Intent.ACTION_SEND, data = null, stream = "https://indexer.example/getnzb/1.nzb"))
        assertNull(NzbIntents.uri(Intent.ACTION_VIEW, data = null, stream = null))
        assertNull(NzbIntents.uri(Intent.ACTION_EDIT, data = document, stream = null))
        assertNull(NzbIntents.uri(Intent.ACTION_SEND_MULTIPLE, data = null, stream = document))
        assertNull(NzbIntents.uri(null, data = document, stream = null))
    }

    @Test
    fun `the title is the file's name without nzb`() {
        assertEquals("Lantern Field", NzbIntents.title("Lantern Field.nzb", "1042", "application/octet-stream"))
        assertEquals("LANTERN.FIELD.2026", NzbIntents.title("LANTERN.FIELD.2026.NZB", null, null))
        // No name from the provider (a file: uri): the uri's own.
        assertEquals("Lantern Field", NzbIntents.title(null, "Lantern Field.nzb", null))
        assertEquals("Lantern Field", NzbIntents.title(" ", "primary:Download/Lantern Field.nzb", null))
    }

    @Test
    fun `a file not named nzb is taken only when the sender says it is one`() {
        assertEquals("1042", NzbIntents.title("1042", "1042", "application/x-nzb"))
        assertEquals("Lantern Field", NzbIntents.title("Lantern Field", null, "application/x-nzb; charset=utf-8"))
        assertEquals("NZB", NzbIntents.title(null, null, "application/x-nzb"))
        assertNull(NzbIntents.title("holiday.mkv", "holiday.mkv", "application/octet-stream"))
        assertNull(NzbIntents.title("notes.txt", null, "text/plain"))
        assertNull(NzbIntents.title(null, "1042", null))
    }

    @Test
    fun `a file uri that needs storage access says so`() {
        val denied = FileNotFoundException("/storage/emulated/0/Download/Lantern Field.nzb: open failed: EACCES (Permission denied)")
        for (e in listOf(denied, SecurityException("Permission Denial"))) {
            val message = describeReadFailure(NzbIntents.readFailure(e, "file"))
            assertTrue("storage" in message && "share" in message.lowercase(), message)
        }
        // A content uri's failure, or any other, keeps its own message.
        assertEquals("Could not read the file: ${denied.message}", describeReadFailure(NzbIntents.readFailure(denied, "content")))
        assertEquals("Could not read the file: gone", describeReadFailure(NzbIntents.readFailure(IOException("gone"), "file")))
    }
}
