package io.skjaere.usenet.app.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * The next of what the channel brings, or null once [timeoutMs] has passed without anything. A select, not a receive
 * inside a timeout: a receive the timeout cancels just after it took something loses it (a channel's receive gives way
 * to its cancellation), and the loops that wait this way would never hear of it again: a phone linking, the player
 * leaving, the TV's word that a play stopped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <E : Any> ReceiveChannel<E>.receiveWithin(timeoutMs: Long): E? = select {
    onReceive { it }
    onTimeout(timeoutMs) { null }
}
