package io.skjaere.usenet.app.collection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The test image loader ([ScaleImages]) as the README screenshots use it: each TMDB image path the screens catalogue
 * names is answered with its own picture from the androidTest assets, and a path no asset answers, or an address that
 * isn't TMDB's image host, fails its load and then the loader's [ScaleImages.restore], so the run fails rather than
 * show a gap. The scale tests' loader, one poster for every path, is unchanged. Nothing reaches the network: the
 * other host is an example.* name the loader refuses before any fetch.
 */
@RunWith(AndroidJUnit4::class)
class ScaleImagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun ScaleImages.load(url: String): ImageResult = runBlocking {
        loader.execute(
            ImageRequest.Builder(context).data(url).size(Size.ORIGINAL)
                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build(),
        )
    }

    private fun ImageResult.size(): Pair<Int, Int> = (this as SuccessResult).image.let { it.width to it.height }

    @Test
    fun theScaleTestsLoaderAnswersEveryTmdbPathWithItsOnePoster() {
        val app = SingletonImageLoader.get(context)
        val images = ScaleImages(context, latencyMs = 0).also { it.install() }
        try {
            assertEquals(185 to 278, images.load("https://image.tmdb.org/t/p/w185/anything.jpg").size())
            assertEquals(185 to 278, images.load("https://image.tmdb.org/t/p/w342/nzs/kelpmoor-poster.jpg").size())
        } finally {
            images.restore()
        }
        assertSame(app, SingletonImageLoader.get(context))
    }

    @Test
    fun theScreensLoaderAnswersEachPathWithItsOwnPicture() {
        val images = ScaleImages.screens(context).also { it.install() }
        try {
            assertEquals(500 to 750, images.load("https://image.tmdb.org/t/p/w342/nzs/kelpmoor-poster.jpg").size())
            assertEquals(1280 to 720, images.load("https://image.tmdb.org/t/p/w780/nzs/kelpmoor-backdrop.jpg").size())
            assertEquals(640 to 360, images.load("https://image.tmdb.org/t/p/w300/nzs/kelpmoor-s01e04.jpg").size())
            assertEquals(500 to 750, images.load("https://image.tmdb.org/t/p/original/nzs/the-ninth-bollard-poster.jpg").size())
        } finally {
            images.restore()
        }
        assertEquals(4, images.fetched.get())
    }

    @Test
    fun aPathNoAssetAnswersFailsItsLoadAndTheRestore() {
        val app = SingletonImageLoader.get(context)
        val images = ScaleImages.screens(context).also { it.install() }
        assertTrue(images.load("https://image.tmdb.org/t/p/w342/nzs/not-in-the-catalogue.jpg") is ErrorResult)
        assertTrue("a TMDB path of its own, not the catalogue's", images.load("https://image.tmdb.org/t/p/w342/q7Zr2x.jpg") is ErrorResult)
        assertTrue("the catalogue itself is no picture", images.load("https://image.tmdb.org/t/p/w342/nzs/catalog.json") is ErrorResult)
        val failure = assertThrows(AssertionError::class.java) { images.restore() }
        listOf("/nzs/not-in-the-catalogue.jpg", "/q7Zr2x.jpg", "/nzs/catalog.json").forEach { assertTrue(failure.message!!, failure.message!!.contains(it)) }
        assertSame("the app's loader is back all the same", app, SingletonImageLoader.get(context))
    }

    @Test
    fun anotherHostIsRefusedAndFailsTheRestore() {
        val images = ScaleImages.screens(context).also { it.install() }
        assertTrue(images.load("https://images.example/t/p/w342/nzs/kelpmoor-poster.jpg") is ErrorResult)
        assertEquals(1, images.refused.get())
        val failure = assertThrows(AssertionError::class.java) { images.restore() }
        assertTrue(failure.message!!, failure.message!!.contains("images.example"))
    }
}
