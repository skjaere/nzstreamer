@file:androidx.annotation.OptIn(UnstableApi::class)

package io.skjaere.usenet.app.cast

import android.content.Context
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import org.slf4j.LoggerFactory

/** The Default Media Receiver (W22, L49): Google's free player, which every Cast device has and which needs no registration. */
internal const val RECEIVER_ID = "CC1AD845"

/** What Cast is started with (L49): the Default Media Receiver; Media3's defaults for the rest. */
internal fun castParams(): CastParams = CastParams.Builder().setReceiverApplicationId(RECEIVER_ID).build()

/** Whether Google Play services can run Google's Cast software on this phone (L50): Google's own check, in a seam (L73). */
fun interface CastAvailability {
    fun available(context: Context): Boolean

    companion object {
        /** Play services present and up to date: an instance method, as Google's reference has it. */
        val Google = CastAvailability { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(it) == ConnectionResult.SUCCESS }
    }
}

/**
 * Starts Google's Cast software (L50), and says whether that start failed, in a seam (L73). The start loads Google's
 * module in the background; a failure there is final for the process (`m3c/Cast.java:191-194`).
 */
interface CastStarter {
    fun start(context: Context)

    fun failed(context: Context): Boolean

    companion object {
        /** Media3's Cast singleton, started with [castParams]. */
        val Google = object : CastStarter {
            override fun start(context: Context) = Cast.getSingletonInstance(context).initialize(castParams())

            override fun failed(context: Context) = Cast.getSingletonInstance(context).castContextLoadFailure != null
        }
    }
}

/** What the gate says as the Play on sheet opens (L50, L111). */
enum class CastGateState {
    /** Google's Cast software is started, or starting: the sheet's Cast group looks for devices. */
    Started,

    /** Play services is missing or out of date, or Cast failed to start: the group says Cast isn't available on this phone. */
    Unavailable,

    /**
     * "Look for Cast devices" is off, or not read yet (L111, L112): nothing of Google's starts, and the group asks first.
     */
    NotAsked,

    /** A TV: nothing of Cast shows or starts there (W28, L67). */
    Television,
}

/**
 * The one way Google's Cast software starts (L50, L67, L111): at the Play on sheet's opening, the first in the process to
 * find everything in place and each later one until it starts. In this order: never on a TV; only once Play services says
 * it is present and up to date (a start without it fails for good, and a phone may update it later; a phone without it
 * never sees "Look for Cast devices"); only once this phone looks for Cast devices, the opt-in (L112: off, or not read
 * yet, the sheet asks first); then once per process, on the main thread. A failed start is final: the sheet says Cast
 * isn't available on this phone each time it opens. Turned off once started, Google's software runs on until the process
 * ends (nothing can stop it), and the sheet asks again. Until the gate starts it, nothing of Google's runs, and nothing is
 * sent to Google. The log says once per process that it started, that the opt-in was found off, or why Cast isn't
 * available (the kind alone), never naming a device or a person.
 */
object CastGate {
    /** Play services' check; the device tests' runner fakes it (L73), so no test but one starts Google's software. */
    @VisibleForTesting
    @Volatile
    var availability: CastAvailability = CastAvailability.Google

    /** Cast's start; the device tests' runner fakes it with the check (L73). */
    @VisibleForTesting
    @Volatile
    var starter: CastStarter = CastStarter.Google

    /** Whether the caller is on the main thread; a JVM test says so itself. */
    @VisibleForTesting
    @Volatile
    var mainThread: () -> Boolean = { Looper.getMainLooper().isCurrentThread }

    /** Where the gate's lines go: the log, at INFO; a JVM test reads them itself. */
    @VisibleForTesting
    @Volatile
    var info: (String) -> Unit = LoggerFactory.getLogger(CastGate::class.java)::info

    @Volatile private var started = false
    @Volatile private var failed = false
    private val told = HashSet<String>()

    /**
     * Cast as the Play on sheet opens on this device ([television]: a TV, where it never starts), with the opt-in as it is
     * read ([looking]: null until settings.json is read, which counts as off), started now if it can be.
     */
    @MainThread
    fun open(context: Context, television: Boolean, looking: Boolean?): CastGateState {
        if (television) return CastGateState.Television
        check(mainThread()) { "Cast starts on the main thread alone" }
        if (!started && !availability.available(context)) {
            tell(UNAVAILABLE + "Google Play services is missing or out of date")
            return CastGateState.Unavailable
        }
        if (looking != true) {
            // Not read yet says nothing: the flag may well be on.
            if (looking == false && !started) tell("cast: not started: Look for Cast devices is off")
            return CastGateState.NotAsked
        }
        if (!started) {
            started = true
            try {
                starter.start(context)
                tell("cast: Google's Cast software started")
            } catch (e: RuntimeException) {
                failed = true
                tell(UNAVAILABLE + "its start failed (${e.javaClass.simpleName})")
            }
        }
        if (!failed && starter.failed(context)) {
            failed = true
            tell(UNAVAILABLE + "Google's Cast software failed to load")
        }
        return if (failed) CastGateState.Unavailable else CastGateState.Started
    }

    /**
     * Whether Play services is present and up to date, by the gate's own check (L146): package facts read on the phone,
     * which start nothing of Google's Cast software and send nothing. Any thread.
     */
    fun available(context: Context): Boolean = availability.available(context)

    /** Each line once per process: the log has what happened, and never a device's or a person's name. */
    private fun tell(line: String) {
        if (told.add(line)) info(line)
    }

    private const val UNAVAILABLE = "Cast isn't available on this phone: "

    /** A test's own process: as if Cast had never been asked for. Google's software, once started, stays started. */
    @VisibleForTesting
    fun forget() {
        started = false
        failed = false
        told.clear()
    }
}
