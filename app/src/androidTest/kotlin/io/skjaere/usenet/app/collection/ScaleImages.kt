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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * TMDB's images for the TV scale tests and the README screenshots, with nothing reaching the network: an image loader as
 * the app makes one for this device (its memory cache by the device's budget), image.tmdb.org addresses answered from the
 * androidTest assets after [latencyMs], as a fetch would be, and any other address refused. For the scale tests every
 * path is one made-up poster (a 185 x 278 JPEG). Given [assetFor], each path (the part after TMDB's size, as
 * "/abc.jpg") is the asset it names, and one it names none for is [unknown]: the screenshots' loader ([screens]).
 * [install] makes it the screens' loader (Coil's singleton, which the posters use) and [restore] puts the app's back:
 * there is no test orchestrator, and later classes check the app's. With [assetFor], [restore] then fails if a path was
 * unknown or an address refused, so a screenshot never shows a gap where a picture should be. It counts what it fetched,
 * what came from its memory cache, what was cancelled or failed, and when each address's image first came ([shown], in
 * elapsedRealtimeNanos).
 */
class ScaleImages(
    private val context: Context,
    val latencyMs: Long = LATENCY_MS,
    private val assetFor: ((path: String) -> String?)? = null,
) {
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    // The scale tests' one poster is read before their timings start, as it always was; the screens' as asked for.
    private val bytes = ConcurrentHashMap<String, ByteArray>().apply { if (assetFor == null) put(ASSET, read(ASSET)) }
    val fetched = AtomicInteger()
    val fromMemory = AtomicInteger()
    val cancelled = AtomicInteger()
    val failed = AtomicInteger()
    val refused = AtomicInteger()
    val shown = ConcurrentHashMap<String, Long>()

    /** TMDB paths no asset answered, and the addresses refused, in order. */
    val unknown = ConcurrentLinkedQueue<String>()
    private val refusedAddresses = ConcurrentLinkedQueue<String>()
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
        if (assetFor != null && (unknown.isNotEmpty() || refusedAddresses.isNotEmpty())) {
            throw AssertionError("the test images had no answer for ${(unknown + refusedAddresses).joinToString()}")
        }
    }

    private fun read(asset: String) = assets.open(asset).use { it.readBytes() }

    /** What the loader holds now: its memory cache's size and its most, in bytes. */
    fun memory(): Pair<Long, Long> = loader.memoryCache?.let { it.size to it.maxSize } ?: (0L to 0L)

    private inner class Posters : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != "http" && data.scheme != "https") return null
            return object : Fetcher {
                override suspend fun fetch(): FetchResult {
                    if (data.authority != TMDB_IMAGES) {
                        refused.incrementAndGet()
                        refusedAddresses += data.toString()
                        throw IllegalStateException("a test asked the network for $data")
                    }
                    val path = TMDB_PATH.matchEntire(data.path.orEmpty())?.groupValues?.get(1)
                    val asset = if (assetFor == null) ASSET else path?.let(assetFor)
                    if (asset == null) {
                        unknown += path ?: data.toString()
                        throw IllegalStateException("no test image answers $data")
                    }
                    val jpeg = bytes.getOrPut(asset) { read(asset) }
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

        /** An image address's path: TMDB's size ("w342", "original"), then the image's own path. */
        private val TMDB_PATH = Regex("/t/p/[^/]+(/.+)")

        /** Where the screens catalogue's pictures are, in the androidTest assets, and the paths it gives them. */
        const val SCREENS = "screens"
        const val SCREENS_PATH = "/nzs/"

        /**
         * The README screenshots' loader: a path "/nzs/<file>.jpg" is the asset screens/<file>.jpg (tools/screens/art.py
         * draws them for the screens catalogue), any other path unknown. No latency: the screenshots wait for every
         * picture anyway.
         */
        fun screens(context: Context): ScaleImages {
            val files = InstrumentationRegistry.getInstrumentation().context.assets.list(SCREENS).orEmpty().toSet()
            return ScaleImages(context, latencyMs = 0) { path ->
                path.removePrefix(SCREENS_PATH).takeIf { path.startsWith(SCREENS_PATH) && it.endsWith(".jpg") && it in files }?.let { "$SCREENS/$it" }
            }
        }
    }
}
