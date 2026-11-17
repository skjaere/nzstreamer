package io.skjaere.usenet.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * Whether the app reads as offline (L410, L438): the one signal Home and the Show page go by, made once in the container
 * from whether the device's default network is [validated] ([AppContainer.validatedNetwork]). Offline once the network
 * has been unvalidated for [AFTER_MILLIS], online again at once: Android reads a switch from one network to another as a
 * loss for a moment ([DefaultNetwork]), and a page that greyed its rows for that moment would flicker. As the app starts
 * the same: a network already gone reads as offline three seconds on. [validated] is a constructor flow, so a test gives
 * its own rather than switching the device's Wi-Fi.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSignal(validated: Flow<Boolean>, scope: CoroutineScope) {
    val offline: StateFlow<Boolean> = validated.distinctUntilChanged()
        .transformLatest { usable ->
            if (usable) {
                emit(false)
            } else {
                // A dip shorter than this is cancelled by the network's return before it says anything.
                delay(AFTER_MILLIS)
                emit(true)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    companion object {
        /** How long the network is gone before the pages say so (L410). */
        const val AFTER_MILLIS = 3_000L
    }
}

/**
 * What a greyed row's tap says offline (L410, L439), on Home and on the Show page alike: "Quiet Meridian S01E03 isn't
 * downloaded. It plays once you're online." for [name], what the row plays.
 */
internal fun isntDownloaded(name: String): String = "$name isn't downloaded. It plays once you're online."
