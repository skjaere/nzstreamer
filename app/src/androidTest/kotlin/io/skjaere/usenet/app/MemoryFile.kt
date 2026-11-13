package io.skjaere.usenet.app

import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * Every play's file from memory: [bytes], whatever its URI says (PlayerActivity.dataSources), without the network. The
 * tests' files are a second long, and the player leaves as a play ends with nothing next (EndOfPlay): each player plays
 * this one over and over ([playOverAndOver]), so it stays while a test looks at it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun memoryFile(bytes: ByteArray): DataSource.Factory = DataSource.Factory {
    object : DataSource {
        private var source: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            playOverAndOver()
            return ByteArrayDataSource(bytes).also { source = it }.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = source!!.read(buffer, offset, length)

        override fun getUri(): Uri? = source?.uri

        override fun close() {
            source?.close()
            source = null
        }
    }
}

/**
 * Has every started player play its file over and over from its start, never ending: called as a player opens its file
 * (on its loading thread, before a byte of it is read), so a second-long file can't end before it is set.
 */
fun playOverAndOver() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val monitor = ActivityLifecycleMonitorRegistry.getInstance()
        listOf(Stage.STARTED, Stage.RESUMED, Stage.PAUSED).flatMap(monitor::getActivitiesInStage).filterIsInstance<PlayerActivity>().distinct()
            .forEach { it.player?.repeatMode = Player.REPEAT_MODE_ONE }
    }
}
