package io.skjaere.usenet.app.settings

import io.skjaere.usenet.player.DecoderSummary
import io.skjaere.usenet.player.DisplayDetails
import io.skjaere.usenet.player.DisplayFacts
import io.skjaere.usenet.player.DisplayModes
import io.skjaere.usenet.player.MatchContentFrameRate
import io.skjaere.usenet.player.ModeSize
import io.skjaere.usenet.player.NativeFacts
import io.skjaere.usenet.player.VideoDecoding
import io.skjaere.usenet.player.formatRate
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.player.tv.PlaybackBudget
import io.skjaere.usenet.player.tv.PlaybackBudgets
import io.skjaere.usenet.releases.Hdr
import io.skjaere.yenc.RapidYencKernel
import java.util.Locale

/** The settings hub's line under This device: "NVIDIA SHIELD Android TV · Android 11". */
fun thisDeviceLine(manufacturer: String, model: String, androidRelease: String): String =
    "${deviceName(manufacturer, model)} · Android $androidRelease"

/** "NVIDIA SHIELD Android TV", as the log header and This device name the device. */
fun deviceName(manufacturer: String, model: String): String = "$manufacturer $model".trim().ifEmpty { "unknown device" }

/**
 * What the log gets once a start from a shared log's [header]: its lines but those [logged] already (the decoder
 * loader logs the display and the decoders itself), each once. On a TV the header can't be read any other way: Copy
 * goes to a clipboard nothing there reads.
 */
fun headerLinesToLog(header: String, logged: List<String>): List<String> =
    header.lines().filter { it.isNotEmpty() && it !in logged }.distinct()

/**
 * Settings › Playback › This device, and the log header's device lines, from the same facts read elsewhere: none is asked
 * of the system here, so the header may build it while a crash is written. The page shows Device, Display, Video, Audio
 * and yEnc decoding in the approved mockups' order and words (TdTvFull, TdPhoneFull); the header says each of those
 * facts too ("The same facts go into the log"). Device facts only: nothing from Settings, no account, no titles, so a
 * screenshot of the page passes on only what the device is.
 *
 * A fact still null is being read ("…", as Cache's sizes), or, once [settled], couldn't be.
 */
data class ThisDevice(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdk: Int,
    /** Whether the device is a TV, as the app's layouts take it (FormFactor, without a debug build's launch extra). */
    val television: Boolean?,
    /** The ABIs the device runs 64-bit and 32-bit processes on, the preferred first (Build.SUPPORTED_64_BIT_ABIS, _32_). */
    val abis64: List<String> = emptyList(),
    val abis32: List<String> = emptyList(),
    val memory: DeviceMemory? = null,
    val display: DisplayFacts? = null,
    /** The refresh rate of the mode the display runs now, as File details read it: the page's Output only. */
    val refreshRate: Float? = null,
    val modes: DisplayModes? = null,
    val decoders: DecoderSummary? = null,
    val native: NativeFacts? = null,
    /** Whether the TV's layouts show, where a debug build's launch extra puts the other's on the device; null: its own. */
    val tvLayouts: Boolean? = null,
    /** Every read has been tried: a fact still null couldn't be read. */
    val settled: Boolean = false,
) {
    /**
     * A fact: its [label], [value] and [note]; a [warning] in the warning colour under them; the HDR formats a value
     * names, shown as chips ([hdr]); [failed], a value that says something didn't work.
     */
    data class Row(val label: String, val value: String, val note: String? = null, val warning: String? = null, val hdr: List<Hdr> = emptyList(), val failed: Boolean = false)

    data class Section(val title: String, val rows: List<Row>)

    val sections: List<Section>
        get() = listOf(
            Section("Device", deviceRows()),
            Section("Display", displayRows()),
            Section("Video", videoRows()),
            Section("Audio", audioRows()),
            Section("yEnc decoding", nativeRows()),
        )

    /**
     * The log header's lines from these facts, after its Memory line: the kind, the play budget, the display's modes and
     * rapidyenc (each once read), then the display and the decoders as the decoder loader logs them. Numbers in the root
     * locale, and no `name=value` or `a:b@c` for the logger's redaction to take a word of.
     */
    val headerLines: List<String>
        get() = listOfNotNull(kindLine(), budgetLine(), modesLine(), nativeLine(), display?.toString()) + decoders?.lines.orEmpty()

    /** A shared log's header: the app's version and build, Android and the device, its memory, then [headerLines]. */
    fun header(appVersion: String, versionCode: Long, debugBuild: Boolean): String =
        logHeader(appVersion, versionCode, debugBuild, androidRelease, sdk, manufacturer, model, memory, headerLines)

    private val tv get() = television == true
    private val unread get() = if (settled) CANT_READ else READING

    /** As PlayerActivity sizes a play's buffer and the engine its streams' read-ahead, by the display read last. */
    private val budget: PlaybackBudget?
        get() {
            val m = memory ?: return null
            val d = display ?: return null
            return PlaybackBudgets.of(m, uhdDisplay = d.height >= UHD_HEIGHT)
        }

    private fun deviceRows(): List<Row> {
        val kind = television?.let { device ->
            val layouts = tvLayouts ?: device
            when {
                device == layouts -> if (device) "TV" else "Phone or tablet"
                device -> "TV, showing the phone’s layouts"
                else -> "Phone or tablet, showing the TV’s layouts"
            }
        }
        val m = memory
        val process = m?.let {
            val abi = (if (it.process64Bit) abis64 else abis32).firstOrNull()
            val others = it.abis.filter { other -> other != abi }
            Row("Process", listOfNotNull("${bits(it)}-bit", abi).joinToString(" · "), others.takeIf { o -> o.isNotEmpty() }?.let { o -> "the device also runs ${o.joinToString()}" })
        }
        val ram = m?.let {
            val low = if (it.lowRam) "a low-RAM device" else "not a low-RAM device"
            Row("Memory", "%.1f GB RAM".format(it.totalRamBytes / GB), "heap ${it.heapLimitBytes / MB} MB (class ${it.memoryClassMb} MB) · $low")
        }
        val play = budget?.let { Row("Play budget", "player buffer ${it.targetBufferBytes / MB} MB · streams ${it.streamBytes / MB} MB ahead", "images: ${images(it)}") }
        return listOf(
            Row("Model", deviceName(manufacturer, model)),
            Row("Android", "$androidRelease (API $sdk)"),
            Row("Kind", kind ?: unread),
            process ?: Row("Process", unread),
            ram ?: Row("Memory", unread),
            play ?: Row("Play budget", unread),
        )
    }

    private fun displayRows(): List<Row> {
        val d = display
        val m = modes
        val rows = if (d == null) {
            listOf("Output", "Largest mode", "Auto quality").map { Row(it, unread) }
        } else {
            listOf(
                Row("Output", DisplayDetails(d, refreshRate).fact?.value ?: UNKNOWN),
                Row("Largest mode", d.largest?.let(::size) ?: UNKNOWN),
                Row("Auto quality", "${d.height}p and below"),
            )
        }
        val rates = m?.let {
            // A TV's rates are those frame-rate matching could switch to; a phone's, the panel's adaptive refresh.
            val note = if (tv) it.size?.let { s -> "at ${size(s)}" } else "the screen’s own rates"
            Row("Refresh rates", if (it.rates.isEmpty()) UNKNOWN else it.rates.joinToString { r -> formatRate(r.toDouble()) } + " Hz", note)
        } ?: Row("Refresh rates", unread)
        val hdr = when {
            d == null -> Row("HDR", unread)
            d.hdr == null -> Row("HDR", CANT_READ)
            else -> {
                val shown = HDR_SHOWN.filter { it in d.hdr!! }
                // The Shield's TV reports HDR10 without Dolby Vision for about 8 s after it wakes (DisplayFacts).
                val note = "just after the TV wakes, Dolby Vision can be missing for a few seconds".takeIf { tv && Hdr.DolbyVision in shown }
                if (shown.isEmpty()) Row("HDR", "None") else Row("HDR", shown.joinToString { it.label }, note, hdr = shown)
            }
        }
        // The system's setting, with a pointer to the app's own switch: where it is, never whether it is on (device facts only).
        val match = when {
            !tv -> null
            sdk < MATCH_FROM -> Row("Match frame rate", "Android $androidRelease doesn’t tell apps this setting", MATCH_NOTE)
            else -> Row("Match frame rate", m?.matchContentFrameRate?.let { MATCH_WORDS.getValue(it) } ?: unread, MATCH_NOTE)
        }
        return rows + listOfNotNull(rates, hdr, match)
    }

    private fun videoRows(): List<Row> {
        val s = decoders ?: return listOf(Row("Decoders", unread))
        val codecs = s.video.filter { !it.tenBit }.map { eight ->
            val ten = s.video.firstOrNull { it.codec == eight.codec && it.tenBit }
            Row(eight.codec.label, decoding(eight, " · "), ten?.let { "10-bit ${decoding(it, ", ")}" })
        }
        val dolbyVision = if (!s.dolbyVision) null else {
            val profiles = s.dolbyVisionProfiles.distinctBy { it.profile }.sortedBy { it.profile }
            when (profiles.size) {
                0 -> Row("Dolby Vision", "a decoder, no profiles listed")
                1 -> Row("Dolby Vision", "profile ${profiles.single().profile}", profiles.single().name)
                else -> Row("Dolby Vision", "profiles ${profiles.joinToString { it.profile.toString() }}", profiles.joinToString { it.name })
            }
        }
        val none = s.noDecoder.map { it.label } + listOfNotNull(Hdr.DolbyVision.label.takeIf { !s.dolbyVision })
        return codecs + listOfNotNull(dolbyVision, none.takeIf { it.isNotEmpty() }?.let { Row("No decoder", it.joinToString()) })
    }

    private fun audioRows(): List<Row> {
        val s = decoders ?: return listOfNotNull(Row("Decoded", unread), Row("Over HDMI", unread).takeIf { tv })
        val passed = s.audioPassedThrough.map { it.label }
        // What a phone passes is rarely anything: said only when it is, where a TV always says it.
        val hdmi = if (tv || passed.isNotEmpty()) Row("Over HDMI", passed.joinToString().ifEmpty { "none" }, PASSTHROUGH_NOTE) else null
        return listOfNotNull(Row("Decoded", s.audioDecoded.joinToString { it.label }.ifEmpty { "none" }), hdmi)
    }

    private fun nativeRows(): List<Row> = when (val n = native) {
        null -> listOf("rapidyenc", "Decode", "CRC32").map { Row(it, unread) }
        is NativeFacts.Failed -> listOf(Row("rapidyenc", "Didn’t load", n.error, failed = true))
        is NativeFacts.Loaded -> listOf(Row("rapidyenc", n.version), kernelRow("Decode", n.decode), kernelRow("CRC32", n.crc))
    }

    private fun kernelRow(label: String, kernel: RapidYencKernel) =
        Row(label, kernelWords(kernel), warning = "slower: this processor’s faster instructions weren’t found".takeIf { kernel == RapidYencKernel.GENERIC })

    private fun kindLine() = television?.let { "Kind: ${if (it) "TV" else "phone or tablet"}" }

    private fun budgetLine() = budget?.let {
        "Budget: player buffer ${it.targetBufferBytes / MB} MB, streams ${it.streamBytes / MB} MB ahead, images ${images(it).replace('’', '\'')}"
    }

    private fun modesLine(): String? {
        val m = modes ?: return null
        val size = m.size
        val rates = if (size == null || m.rates.isEmpty()) "unknown" else m.rates.joinToString { formatRate(it.toDouble(), Locale.ROOT) } + " Hz at $size"
        val match = if (sdk < MATCH_FROM) "not told below Android 12" else m.matchContentFrameRate?.let { MATCH_WORDS.getValue(it).lowercase() } ?: "unknown"
        return "Display modes: $rates; match content frame rate: $match"
    }

    private fun nativeLine() = when (val n = native) {
        null -> null
        is NativeFacts.Failed -> "Native: rapidyenc didn't load: ${n.error}"
        is NativeFacts.Loaded -> "Native: rapidyenc ${n.version}, decode ${kernelWords(n.decode)}, CRC ${kernelWords(n.crc)}"
    }

    private companion object {
        const val READING = "…"
        const val CANT_READ = "Can’t be read"
        const val UNKNOWN = "unknown"
        /** The route is followed while the app runs (AudioRouteWatcher): a receiver switched on or off since it started counts. */
        const val PASSTHROUGH_NOTE = "passed through to the TV or receiver, as it is now: switching a receiver on or off updates this"

        /** Where the app's own frame-rate switch is (Quality and languages' last row on a TV). */
        const val MATCH_NOTE = "the app can switch the TV itself: Quality and languages › Match the TV to the video’s frame rate"

        /** Where PlayerActivity gives a play the 4K screen's buffer (PlaybackBudgets.of's uhdDisplay). */
        const val UHD_HEIGHT = 2160

        /** Android 12 (API 31): DisplayManager.getMatchContentFrameRateUserPreference. */
        const val MATCH_FROM = 31
        const val MB = 1024L * 1024
        const val GB = 1024.0 * MB

        /** The HDR formats in the order File details name them. */
        val HDR_SHOWN = listOf(Hdr.DolbyVision, Hdr.Hdr10, Hdr.Hdr10Plus, Hdr.Hlg)

        val MATCH_WORDS = mapOf(
            MatchContentFrameRate.Always to "Always", MatchContentFrameRate.OnlyWhenSeamless to "Only when seamless",
            MatchContentFrameRate.Never to "Never", MatchContentFrameRate.Unknown to "Not told",
        )

        fun bits(memory: DeviceMemory) = if (memory.process64Bit) 64 else 32

        fun size(s: ModeSize) = "${s.width} × ${s.height}"

        fun images(budget: PlaybackBudget) = budget.imageMemoryBytes?.let { "${it / MB} MB" } ?: "the image loader’s default"

        /** "up to 2160p · hardware": how large a codec plays, and how much of that in hardware; [joint] between. */
        fun decoding(v: VideoDecoding, joint: String): String {
            val max = v.maxHeight
            val size = when (max) {
                null -> "size unknown"
                0 -> "below 480p"
                else -> "up to ${max}p"
            }
            val hardware = when (val hw = v.hardwareMaxHeight) {
                null -> "hardware unknown"
                0 -> "software"
                else -> if (max != null && hw >= max) "hardware" else "hardware up to ${hw}p"
            }
            return "$size$joint$hardware"
        }

        fun kernelWords(kernel: RapidYencKernel): String = when (kernel) {
            RapidYencKernel.NEON -> "NEON"
            RapidYencKernel.ARMCRC -> "ARM CRC32"
            RapidYencKernel.ARMPMULL -> "ARM PMULL"
            RapidYencKernel.RVV -> "RISC-V vector"
            RapidYencKernel.ZBC -> "RISC-V Zbc"
            RapidYencKernel.GENERIC -> "generic code"
            RapidYencKernel.UNKNOWN -> "unknown"
            // SSE2, SSSE3, AVX, AVX2, VBMI2, PCLMUL, VPCLMUL: as Intel names them.
            else -> kernel.name
        }
    }
}
