package io.skjaere.usenet.app.collection

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import io.skjaere.usenet.app.appImageLoader
import io.skjaere.usenet.app.container
import kotlinx.coroutines.delay
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * TMDB's images for the TV scale tests, with nothing reaching the network: an image loader as the app makes one for this
 * device (its memory cache by the device's budget), every image.tmdb.org address answered with one made-up poster (a
 * 185 x 278 JPEG from the androidTest assets) after [latencyMs], as a fetch would be, and any other address refused.
 * [install] makes it the screens' loader (Coil's singleton, which the posters use) and [restore] puts the app's back:
 * there is no test orchestrator, and later classes check the app's. It counts what it fetched, what came from its memory
 * cache, what was cancelled or failed, and when each address's image first came ([shown], in elapsedRealtimeNanos).
 */
class ScaleImages(private val context: Context, val latencyMs: Long = LATENCY_MS) {
    private val jpeg = InstrumentationRegistry.getInstrumentation().context.assets.open(ASSET).use { it.readBytes() }
    val fetched = AtomicInteger()
    val fromMemory = AtomicInteger()
    val cancelled = AtomicInteger()
    val failed = AtomicInteger()
    val refused = AtomicInteger()
    val shown = ConcurrentHashMap<String, Long>()
    private var app: ImageLoader? = null
    lateinit var loader: ImageLoader
        private set

    fun install() {
        app = SingletonImageLoader.get(context)
        loader = appImageLoader(context, context.container.memory).newBuilder()
            .components { add(Posters()) }
            .eventListener(Events())
            .build()
        SingletonImageLoader.setUnsafe(loader)
    }

    fun restore() {
        app?.let { SingletonImageLoader.setUnsafe(it) }
        app = null
        if (::loader.isInitialized) loader.shutdown()
    }

    /** What the loader holds now: its memory cache's size and its most, in bytes. */
    fun memory(): Pair<Long, Long> = loader.memoryCache?.let { it.size to it.maxSize } ?: (0L to 0L)

    private inner class Posters : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "http" && data.scheme != "https") return null
            return object : Fetcher {
                override suspend fun fetch(): FetchResult {
                    if (data.authority != TMDB_IMAGES) {
                        refused.incrementAndGet()
                        throw IllegalStateException("a scale test asked the network for $data")
                    }
                    delay(latencyMs)
                    fetched.incrementAndGet()
                    return SourceFetchResult(ImageSource(Buffer().write(jpeg), options.fileSystem), "image/jpeg", DataSource.NETWORK)
                }
            }
        }
    }

    private inner class Events : coil3.EventListener() {
        override fun onSuccess(request: ImageRequest, result: SuccessResult) {
            if (result.dataSource == DataSource.MEMORY_CACHE) fromMemory.incrementAndGet()
            shown.putIfAbsent(request.data.toString(), SystemClock.elapsedRealtimeNanos())
        }

        override fun onCancel(request: ImageRequest) {
            cancelled.incrementAndGet()
        }

        override fun onError(request: ImageRequest, result: ErrorResult) {
            failed.incrementAndGet()
        }
    }

    companion object {
        /** A fetch's time on a TV's network, about; set, so runs compare. */
        const val LATENCY_MS = 40L
        const val ASSET = "scale-poster.jpg"
        private const val TMDB_IMAGES = "image.tmdb.org"
    }
}
