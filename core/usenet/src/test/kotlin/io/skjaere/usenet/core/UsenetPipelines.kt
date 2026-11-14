package io.skjaere.usenet.core

import io.skjaere.nntp.NntpDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.io.path.createTempDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A store no test writes to: the pipeline never keeps what plays, and the tests' probes don't either, as the app's
 * does ([PlaybackTarget.keep]). A test whose probe keeps passes its own.
 */
val unusedStore: PreparedNzbStore by lazy { PreparedNzbStore(createTempDirectory("usenet-source").toFile().apply { deleteOnExit() }) }

/** A pipeline of Usenet's releases alone, each tried through a [UsenetSource] of these. */
fun usenetPipeline(
    source: NzbSource,
    availability: AvailabilityBackend,
    preparer: NzbPreparer,
    probe: TrackProbe = TrackProbe.None,
    maxAttempts: Int = 3,
    dispatcher: CoroutineDispatcher = NntpDispatchers.IO,
    timeSource: TimeSource = TimeSource.Monotonic,
    providersLeftOut: () -> Boolean = { false },
    providerFallback: () -> Boolean = { false },
    tried: TriedReleases = TriedReleases.None,
    chooser: FileChooser = FileChooser.None,
    stepWarnAfter: Duration = 20.seconds,
    store: PreparedNzbStore = unusedStore,
): PlayPipeline {
    val usenet = UsenetSource(source, availability, preparer, store, providersLeftOut, providerFallback, chooser)
    return PlayPipeline({ usenet }, probe, maxAttempts, dispatcher, timeSource, tried, stepWarnAfter)
}
