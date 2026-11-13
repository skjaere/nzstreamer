package io.skjaere.usenet.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import io.skjaere.usenet.player.tv.DeviceMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The image loader's memory cache follows the device's playback budget: on a low-RAM box the
 * budget's few MB, so posters don't crowd a play's buffers; elsewhere Coil's own default.
 */
@RunWith(AndroidJUnit4::class)
class ImageMemoryCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun memory(lowRam: Boolean, ramMb: Long) = DeviceMemory(
        lowRam = lowRam, memoryClassMb = 192, totalRamBytes = ramMb * MB, process64Bit = false, abis = listOf("armeabi-v7a"),
    )

    @Test
    fun aLowRamBoxKeepsTheBudgetsFewMegabytesOfImages() {
        assertEquals(8 * MB, appImageLoader(context, memory(lowRam = true, ramMb = 1024)).memoryCache?.maxSize)
        assertEquals(12 * MB, appImageLoader(context, memory(lowRam = true, ramMb = 1536)).memoryCache?.maxSize)
    }

    @Test
    fun elsewhereOrWithTheMemoryUnknownCoilsDefault() {
        val default = ImageLoader(context).memoryCache?.maxSize
        assertEquals(default, appImageLoader(context, memory(lowRam = false, ramMb = 4096)).memoryCache?.maxSize)
        assertEquals(default, appImageLoader(context, null).memoryCache?.maxSize)
    }

    /**
     * The app is Coil's maker of the loader every screen uses, and makes it from the device's memory:
     * a low-RAM box's gets the budget's few MB. The lane's devices aren't low-RAM, so their loader is
     * Coil's default size, which Coil's own would be too; the box's memory is stood in here.
     */
    @Test
    fun theAppMakesTheScreensLoaderFromTheDevicesMemory() {
        val factory = context.applicationContext as? SingletonImageLoader.Factory
            ?: throw AssertionError("the app isn't Coil's SingletonImageLoader.Factory: the screens get Coil's own loader")
        val app = context.applicationContext as UsenetApplication
        val device = app.imageLoaderMemory
        app.imageLoaderMemory = { memory(lowRam = true, ramMb = 1024) }
        try {
            val loader = factory.newImageLoader(context)
            assertEquals(8 * MB, loader.memoryCache?.maxSize)
            assertNotNull(loader.diskCache)
        } finally {
            app.imageLoaderMemory = device
        }
    }

    /** The app's loader is made by it, for this device, and keeps Coil's disk cache, which Settings › Cache sizes and clears. */
    @Test
    fun theAppsLoaderFollowsThisDeviceAndKeepsItsDiskCache() {
        val loader = SingletonImageLoader.get(context)
        assertEquals(appImageLoader(context, context.container.memory).memoryCache?.maxSize, loader.memoryCache?.maxSize)
        assertNotNull(loader.diskCache)
    }

    private companion object {
        const val MB = 1024L * 1024
    }
}
