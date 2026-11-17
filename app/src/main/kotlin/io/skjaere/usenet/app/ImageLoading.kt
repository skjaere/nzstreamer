package io.skjaere.usenet.app

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.player.tv.PlaybackBudgets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The app's image loader (posters, stills, backdrops), Coil's own but for its memory cache: on a
 * low-RAM device the playback budget's few MB, so the images a screen showed don't crowd a play's
 * buffers (TV-ME); elsewhere, or with the [memory] unknown, Coil's default share of the heap. Its
 * disk cache stays Coil's, which Settings › Cache sizes and clears.
 */
fun appImageLoader(context: PlatformContext, memory: DeviceMemory?): ImageLoader {
    // The budget's images don't depend on the display, only its buffer does.
    val bytes = memory?.let { PlaybackBudgets.of(it, uhdDisplay = false).imageMemoryBytes }
    return ImageLoader.Builder(context)
        .apply { if (bytes != null) memoryCache { MemoryCache.Builder().maxSizeBytes(bytes).build() } }
        .build()
}

/**
 * The picture at [url] as JPEG bytes, through the app's image loader and its caches: what a Cast session's notification
 * shows, and what a download keeps of its poster and still (L411). Null where none came.
 */
suspend fun jpegOf(context: Context, url: String): ByteArray? {
    val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).allowHardware(false).build())
    val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
    return withContext(Dispatchers.Default) { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray() }
}

private const val JPEG_QUALITY = 90
