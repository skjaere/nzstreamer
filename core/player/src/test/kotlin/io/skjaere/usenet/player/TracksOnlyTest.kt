package io.skjaere.usenet.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import org.junit.Test
import kotlin.test.assertEquals

@androidx.annotation.OptIn(UnstableApi::class)
class TracksOnlyTest {
    /**
     * Kotlin's `by` forwards only LoadControl's abstract method, not its default ones. One the probe's
     * load control doesn't forward itself never reaches DefaultLoadControl, which then doesn't know
     * the player and fails on the playback thread; one Media3 adds later must be forwarded too.
     */
    @Test
    fun `the probe's load control forwards every method media3 calls`() {
        val own = TracksOnly::class.java.declaredMethods.map { it.name to it.parameterTypes.toList() }.toSet()
        val missing = LoadControl::class.java.methods
            .filter { it.isDefault && !it.isAnnotationPresent(java.lang.Deprecated::class.java) }
            .filter { (it.name to it.parameterTypes.toList()) !in own }
            .map { "${it.name}(${it.parameterTypes.joinToString { type -> type.simpleName }})" }
            .sorted()
        assertEquals(emptyList(), missing, "LoadControl methods TracksOnly doesn't forward")
    }
}
