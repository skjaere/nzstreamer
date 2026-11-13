package io.skjaere.usenet.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The device's local date: now, at each local midnight after, and again on every return to the
 * [foreground] (a delay doesn't run in deep sleep, and the zone may have changed). The lists' NEW,
 * "Out 12 Mar", air dates and staleness all count days by it.
 */
class Today(private val clock: Clock, private val foreground: Flow<Boolean>) {
    val dates: Flow<LocalDate> = channelFlow {
        launch { foreground.collect { if (it) send(now().toLocalDate()) } }
        while (true) {
            val now = now()
            send(now.toLocalDate())
            // A millisecond past midnight, so the next read is the new day's.
            delay(Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.zone)).toMillis() + 1)
        }
    }.distinctUntilChanged()

    private fun now(): ZonedDateTime = ZonedDateTime.now(clock)
}

/** The system clock in the device's zone as it is at each read: the user may change it while the app runs. */
object DeviceClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()

    override fun withZone(zone: ZoneId): Clock = system(zone)

    override fun instant(): Instant = Instant.now()

    override fun millis(): Long = System.currentTimeMillis()
}
