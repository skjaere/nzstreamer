package io.skjaere.usenet.app

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.memory.MemoryCache
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.player.tv.PlaybackBudgets

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
