@file:androidx.annotation.OptIn(UnstableApi::class)

package io.skjaere.usenet.app.cast

import androidx.core.net.toUri
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.common.images.WebImage
import io.skjaere.usenet.app.casting.CastMetadata
import java.util.UUID

/**
 * The items cast plays send their device (L62, L63), as Media3's Cast player converts them for the LOAD. Each load's item
 * has an id of its own, opaque and new ([item]): the content id the device reports, and the session's media id, which
 * Media3 publishes to other apps, so it names nothing of the release. Its token URL goes to the device alone: the session's
 * item has no URI, and this keeps the URL by the id. The device is told the container's type, a buffered stream, the
 * play's words and TMDB's picture (L89), and no custom data. What the device reports of its own load comes back as the
 * load's item; another sender's media on the Default Media Receiver, which other apps share (W32), as an item of its id
 * alone, never a throw.
 */
internal object CastItems : MediaItemConverter {
    /** What a load sent: its item, and what the device is told of it. */
    private class Sent(val item: MediaItem, val url: String, val mimeType: String, val said: CastMetadata)

    /** The last loads' items, by id, oldest first: the device plays one, and Media3 keeps its own while it does. */
    private val sent = LinkedHashMap<String, Sent>()

    /**
     * A load's new item: [said] as the session says it, with [artwork], the still decoded for it (L58), on a line that names
     * the [device] as Android [sdkInt] puts it (L120); [url] for the device, which is told [said]'s own words alone.
     */
    fun item(url: String, mimeType: String, said: CastMetadata, artwork: ByteArray?, device: String, sdkInt: Int): MediaItem {
        val id = UUID.randomUUID().toString().replace("-", "")
        val item = MediaItem.Builder().setMediaId(id).setMediaMetadata(said.session(artwork, device, sdkInt)).build()
        synchronized(sent) {
            sent[id] = Sent(item, url, mimeType, said)
            while (sent.size > KEPT) sent.remove(sent.keys.first())
        }
        return item
    }

    /** Whether [id] is one of the last loads' (L154): what the device has is this phone's. */
    fun made(id: String): Boolean = synchronized(sent) { id in sent }

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val load = synchronized(sent) { sent[mediaItem.mediaId] } ?: throw IllegalArgumentException("not an item of this phone's loads")
        val said = load.said
        val metadata = MediaMetadata(if (said.episode) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, said.title)
            said.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            said.imageUrl?.let { addImage(WebImage(it.toUri())) }
        }
        val media = MediaInfo.Builder(mediaItem.mediaId)
            .setContentUrl(load.url)
            .setContentType(load.mimeType)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .build()
        return MediaQueueItem.Builder(media).build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        val id = mediaQueueItem.media?.contentId.orEmpty()
        return synchronized(sent) { sent[id] }?.item ?: MediaItem.Builder().setMediaId(id).build()
    }

    /** Loads kept: a play, the one it replaces there (L84), and room for Try again. */
    private const val KEPT = 8
}
