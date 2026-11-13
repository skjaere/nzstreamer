package io.skjaere.usenet.player.tv

import androidx.media3.exoplayer.DefaultLoadControl
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PlaybackBudgetTest {
    private val mb = 1024 * 1024
    private val gb = 1024L * mb

    private fun memory(totalRamBytes: Long, lowRam: Boolean = true, memoryClassMb: Int = 192, heapLimitMb: Int = memoryClassMb) =
        DeviceMemory(lowRam, memoryClassMb, totalRamBytes, process64Bit = true, abis = listOf("arm64-v8a", "armeabi-v7a"), heapLimitBytes = heapLimitMb * 1024L * 1024)

    /** Google's TV memory page: media buffers of 40-60 MB on a 1 GB box playing 1080p. */
    @Test
    fun `a low-RAM box of 1 GB buffers 48 MB, holds 8 MB ahead of it and keeps 8 MB of images`() {
        val budget = PlaybackBudget(48 * mb, streamBytes = 8L * mb, imageMemoryBytes = 8L * mb)
        listOf(gb * 9 / 10, gb, gb * 11 / 10).forEach { ram ->
            listOf(false, true).forEach { uhd -> assertEquals(budget, PlaybackBudgets.of(memory(ram), uhd), "$ram bytes, uhd $uhd") }
        }
    }

    /** 60-80 MB at 1.5 GB and 1080p; 80-100 MB at 1.5 GB and 2160p. */
    @Test
    fun `a low-RAM box of 1,5 GB buffers 64 MB, or 80 MB on a 4K screen, and holds 12 MB ahead`() {
        listOf(gb * 11 / 10 + 1, gb * 3 / 2, gb * 16 / 10).forEach { ram ->
            assertEquals(PlaybackBudget(64 * mb, 12L * mb, 12L * mb), PlaybackBudgets.of(memory(ram), uhdDisplay = false), "$ram bytes")
            assertEquals(PlaybackBudget(80 * mb, 12L * mb, 12L * mb), PlaybackBudgets.of(memory(ram), uhdDisplay = true), "$ram bytes")
        }
    }

    /** An OEM may flag a larger device low RAM: kept to the conservative tier. */
    @Test
    fun `a larger device flagged low RAM buffers 64 MB`() {
        listOf(gb * 16 / 10 + 1, 2 * gb, 4 * gb).forEach { ram ->
            listOf(false, true).forEach { uhd -> assertEquals(PlaybackBudget(64 * mb, 12L * mb, 12L * mb), PlaybackBudgets.of(memory(ram), uhd), "$ram bytes, uhd $uhd") }
        }
    }

    /**
     * Not low RAM: a sixth of the heap the process may grow to, no less than 64 MB and no more than
     * Media3's own streaming target (125 MiB of video and 12.5 MiB of audio); a tenth of that heap
     * held ahead of the player, 16 to 64 MB; Coil as it is. At 0.4 of the heap, a TV box's 512 MB
     * large heap gave the whole 137.5 MiB, and a 4K remux's collections hitched playback; 80 MB
     * there didn't (tried on the Shield).
     */
    @Test
    fun `any other device buffers a sixth of its heap, up to Media3's target`() {
        val media3 = DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE
        assertEquals(144_179_200, media3)
        listOf(
            1024 to (media3 to 64L * mb), 768 to (134_217_728 to 64L * mb), 576 to (100_663_296 to 60_397_977L),
            512 to (89_478_485 to 53_687_091L), 384 to (64 * mb to 40_265_318L), 256 to (64 * mb to 26_843_545L),
            192 to (64 * mb to 20_132_659L), 160 to (64 * mb to 16L * mb), 128 to (64 * mb to 16L * mb), 64 to (64 * mb to 16L * mb),
        ).forEach { (heap, expected) ->
            val (target, ahead) = expected
            listOf(false, true).forEach { uhd ->
                assertEquals(PlaybackBudget(target, ahead, null), PlaybackBudgets.of(memory(4 * gb, lowRam = false, memoryClassMb = heap), uhd), "heap $heap MB, uhd $uhd")
            }
        }
        assertEquals(PlaybackBudget(64 * mb, 16L * mb, null), PlaybackBudgets.of(memory(gb, lowRam = false, memoryClassMb = 96), uhdDisplay = false))
        assertEquals(64L * mb, PlaybackBudgets.streamBytes(memory(8 * gb, lowRam = false, memoryClassMb = 1024)))
    }

    /** With the large heap (the Shield's is 512 MB beside a 192 MB heap class), the large one counts. */
    @Test
    fun `the heap the process may grow to sizes it, not the heap class`() {
        val shield = memory(3 * gb, lowRam = false, memoryClassMb = 192, heapLimitMb = 512)
        assertEquals(PlaybackBudget(89_478_485, 53_687_091L, null), PlaybackBudgets.of(shield, uhdDisplay = true))
    }

    @Test
    fun `neither a 32-bit process nor the ABIs change it`() {
        listOf(memory(gb), memory(gb * 3 / 2), memory(2 * gb), memory(4 * gb, lowRam = false)).forEach { m ->
            val thirtyTwo = m.copy(process64Bit = false, abis = listOf("armeabi-v7a", "armeabi"))
            listOf(false, true).forEach { uhd -> assertEquals(PlaybackBudgets.of(m, uhd), PlaybackBudgets.of(thirtyTwo, uhd)) }
        }
    }
}
