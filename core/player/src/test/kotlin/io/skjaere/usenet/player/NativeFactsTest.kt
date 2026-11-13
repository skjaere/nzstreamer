package io.skjaere.usenet.player

import io.skjaere.yenc.RapidYencKernel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NativeFacts]: rapidyenc's version and the kernels it picked for decoding and CRC32, or why it didn't load, read off the
 * main thread once a process ([NativeFactsLoader]).
 */
class NativeFactsTest {
    private val fakeMain = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }.asCoroutineDispatcher()
    private val fakeIo = Executors.newSingleThreadExecutor { Thread(it, "fake-io") }.asCoroutineDispatcher()

    @After
    fun stop() {
        fakeMain.close()
        fakeIo.close()
    }

    @Test
    fun `a loaded library says its version and its decode and CRC kernels`() {
        val facts = NativeFacts.of(version = { Triple(1, 1, 1) }, decode = { RapidYencKernel.NEON }, crc = { RapidYencKernel.ARMPMULL })
        assertEquals(NativeFacts.Loaded(version = "1.1.1", decode = RapidYencKernel.NEON, crc = RapidYencKernel.ARMPMULL), facts)
    }

    /**
     * JNA throws UnsatisfiedLinkError at the first touch of a library it can't load, and NoClassDefFoundError at every one
     * after; a failure in the binding's own set-up comes wrapped in ExceptionInInitializerError, its message on the cause.
     */
    @Test
    fun `a library that doesn't load is kept as the fact, with the error's words`() {
        val missing = UnsatisfiedLinkError("Unable to load library 'rapidyenc'")
        assertEquals(NativeFacts.Failed("Unable to load library 'rapidyenc'"), NativeFacts.of({ throw missing }, { RapidYencKernel.NEON }, { RapidYencKernel.NEON }))
        assertEquals(
            NativeFacts.Failed("Could not initialize class io.skjaere.yenc.RapidYencLibrary"),
            NativeFacts.of({ Triple(1, 1, 1) }, { throw NoClassDefFoundError("Could not initialize class io.skjaere.yenc.RapidYencLibrary") }, { RapidYencKernel.NEON }),
        )
        assertEquals(
            NativeFacts.Failed("no symbol rapidyenc_version"),
            NativeFacts.of({ throw ExceptionInInitializerError(IllegalArgumentException("no symbol rapidyenc_version")) }, { RapidYencKernel.NEON }, { RapidYencKernel.NEON }),
        )
        assertEquals(NativeFacts.Failed("UnsatisfiedLinkError"), NativeFacts.of({ throw UnsatisfiedLinkError() }, { RapidYencKernel.NEON }, { RapidYencKernel.NEON }))
    }

    /**
     * JNA builds its load failure from each place it tried, a line each ("Unable to load library 'rapidyenc':", then
     * every attempt's error): kept as one line, so the log header's Native line stays one line and This device one note.
     */
    @Test
    fun `JNA's error of several lines is kept as one`() {
        val jna = UnsatisfiedLinkError(
            "Unable to load library 'rapidyenc':\n" +
                "dlopen failed: library \"librapidyenc.so\" not found\n" +
                "Native library (android-arm/librapidyenc.so) not found in resource path (.)",
        )
        val facts = NativeFacts.of({ throw jna }, { RapidYencKernel.NEON }, { RapidYencKernel.NEON })
        assertEquals(
            NativeFacts.Failed(
                "Unable to load library 'rapidyenc': dlopen failed: library \"librapidyenc.so\" not found; " +
                    "Native library (android-arm/librapidyenc.so) not found in resource path (.)",
            ),
            facts,
        )
        // Blank lines and the spaces around a line go too.
        assertEquals(NativeFacts.Failed("dlopen failed; no symbol"), NativeFacts.of({ throw UnsatisfiedLinkError("  dlopen failed \n\n no symbol\n") }, { RapidYencKernel.NEON }, { RapidYencKernel.NEON }))
    }

    @Test
    fun `get from the main thread loads the library on the given dispatcher, once, and the header's latest follows`() = runBlocking<Unit> {
        val readOn = mutableListOf<String>()
        val loader = NativeFactsLoader(fakeIo) {
            readOn += Thread.currentThread().name
            NativeFacts.Loaded("1.1.1", RapidYencKernel.AVX, RapidYencKernel.PCLMUL)
        }
        assertNull(loader.latest)

        val facts = List(2) { withContext(fakeMain) { loader.get() } }

        assertEquals(1, readOn.size)
        // A coroutine's debug name may follow the thread's.
        assertTrue(readOn.single().startsWith("fake-io"), "read on ${readOn.single()}")
        assertEquals(NativeFacts.Loaded("1.1.1", RapidYencKernel.AVX, RapidYencKernel.PCLMUL), facts.distinct().single())
        assertEquals(facts.first(), loader.latest)
    }

    /** A library that didn't load won't load later in the same process: the failure is the fact, read once. */
    @Test
    fun `a failed load is read once and kept`() = runBlocking<Unit> {
        var reads = 0
        val loader = NativeFactsLoader(fakeIo) { reads++; NativeFacts.Failed("dlopen failed") }

        val facts = List(3) { loader.get() }

        assertEquals(1, reads)
        assertTrue(facts.all { it == NativeFacts.Failed("dlopen failed") }, "$facts")
    }
}
