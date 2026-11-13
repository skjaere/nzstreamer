package io.skjaere.usenet.player

import io.skjaere.yenc.RapidYenc
import io.skjaere.yenc.RapidYencKernel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * rapidyenc as this process loaded it, for This device and the log header: its version and the kernels it picked for
 * decoding and CRC32 (by asking the processor), or why it didn't load.
 */
sealed interface NativeFacts {
    data class Loaded(val version: String, val decode: RapidYencKernel, val crc: RapidYencKernel) : NativeFacts

    /** The library didn't load; [error] is the error's own words, on one line. */
    data class Failed(val error: String) : NativeFacts

    companion object {
        /**
         * Asks the library: the first call loads it (JNA's dlopen, rapidyenc's set-up), so never on the main thread
         * ([NativeFactsLoader] moves it off). JNA throws UnsatisfiedLinkError at the first touch of a library it can't
         * load and NoClassDefFoundError at every one after, and a failure in the binding's set-up comes wrapped in
         * ExceptionInInitializerError: all LinkageErrors.
         */
        internal fun of(version: () -> Triple<Int, Int, Int>, decode: () -> RapidYencKernel, crc: () -> RapidYencKernel): NativeFacts = try {
            val (major, minor, patch) = version()
            Loaded("$major.$minor.$patch", decode(), crc())
        } catch (e: LinkageError) {
            Failed(oneLine(e.message) ?: oneLine(e.cause?.message) ?: e.javaClass.simpleName)
        }

        /**
         * JNA's load failure is a line for each place it tried ("Unable to load library 'rapidyenc':", then each
         * attempt's error): joined, so the header's Native line stays one line and the log one INFO line. Null for none.
         */
        private fun oneLine(message: String?): String? =
            message?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.reduceOrNull { said, next -> if (said.endsWith(':')) "$said $next" else "$said; $next" }
    }
}

/**
 * [NativeFacts] read once a process on [dispatcher] (loading the library reads it from storage), kept, a failure too: a
 * library that didn't load won't later in the same process. Safe to call from the main thread.
 */
class NativeFactsLoader internal constructor(private val dispatcher: CoroutineDispatcher, read: () -> NativeFacts) {
    constructor() : this(Dispatchers.IO, { NativeFacts.of({ RapidYenc.version() }, { RapidYenc.decodeKernel() }, { RapidYenc.crcKernel() }) })

    private val facts = lazy(read)

    /** What [get] read, for the log header, which never loads anything itself; null before. */
    @Volatile
    var latest: NativeFacts? = null
        private set

    suspend fun get(): NativeFacts =
        (if (facts.isInitialized()) facts.value else withContext(dispatcher) { facts.value }).also { latest = it }
}
