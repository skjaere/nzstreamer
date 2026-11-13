package io.skjaere.usenet.app.play

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import io.skjaere.usenet.app.debrid.ServiceRegistry
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FactRow
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.pagesThrough
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.core.FileListing
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.player.Fact
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.player.UsenetDataSource
import io.skjaere.usenet.player.VideoDetails
import io.skjaere.usenet.releases.Hdr
import io.skjaere.usenet.releases.ReleaseParser

/**
 * What File details name a play's source by, as the settings keep it: the [indexers] (Usenet's), the [torrentIndexers] and
 * the debrid [services] accounts.
 */
data class SourceNames(
    val indexers: List<IndexerRecord>,
    val torrentIndexers: List<TorrentIndexerRecord> = emptyList(),
    val services: List<ServiceAccount> = emptyList(),
)

/**
 * Where the file playing came from, as File details show it ([FileDetails]): the release's name, the file's name and
 * size, the indexer, and the providers or, for a debrid play, the service (L207). Only what the panel shows, names, hosts
 * and sizes, is taken: never the link the NZB is downloaded by (it keeps the account's tokens), the guid, the NZB's id,
 * its archive password or poster, an indexer's address or a provider's login; nor a debrid file's link, its torrent's
 * hash or anything of the account but its service's name. A screenshot of the panel passes on what it shows.
 */
data class FileOrigin(
    val release: String?,
    val fileName: String?,
    val fileSize: Long?,
    /** The Indexer row: the indexer's name, [REMOVED], or [OPENED_FILE]; null until the settings are read. */
    val indexer: String?,
    /** The providers in use, in the order articles are asked of them, each by its name or else its host. */
    val providers: List<String>,
    /** How many connections a stream fetches over at once, as the engine sets it. */
    val connections: Int?,
    /** A debrid play's Service row: the service's name, or [SIGNED_OUT]; null for another play, or until the settings are read. */
    val service: String? = null,
) {
    companion object {
        const val REMOVED = "An indexer since removed"
        const val OPENED_FILE = "Opened from an NZB file"
        const val SIGNED_OUT = "A service since signed out of"

        /**
         * The origin of [launch]'s play of [uri], or of a [uri] opened from an NZB file (no launch: the release is named
         * by the NZB's title). The file's name and size are the launch's release's, else the URI's ([PlayUris]), else
         * those of the file in [listing], the release's files as its source keeps them (a Usenet play's prepared NZB;
         * null when no longer held). Nothing comes from the indexer's search item, which a Resume doesn't have: it reads
         * as the play from search did. The indexer is named from [names]' indexers of the release's source (a torrent's
         * from the torrent indexers), disabled ones included (null until they are read); one no longer there is said to be
         * removed. A debrid play names the service of the [account] its listing came from in place of the providers.
         */
        fun of(launch: PlayerLaunch?, uri: String, listing: FileListing<*>?, names: SourceNames?, providers: List<ProviderConfig>, connections: Int?, account: String? = null): FileOrigin {
            val release = launch?.release
            val target = PlayUris.parse(uri)
            val name = release?.fileName ?: target?.name
            val size = release?.fileSize ?: target?.size
            val stored = if (name != null && size != null) null else listing?.find(release?.fileIndex ?: target?.fileIndex ?: 0, name, size)
            val torrent = release?.source.let { it != null && it != PlayCandidate.USENET }
            val indexer = when {
                launch == null -> OPENED_FILE
                names == null -> null
                torrent -> names.torrentIndexers.firstOrNull { it.id == launch.release.indexerId }?.name ?: REMOVED
                else -> names.indexers.firstOrNull { it.id == launch.release.indexerId }?.name ?: REMOVED
            }
            val fromService = target is PlayUris.Target.Service
            val service = names?.takeIf { fromService }?.let { n ->
                n.services.firstOrNull { it.id == account }?.let(ServiceRegistry::kindOf)?.displayName ?: SIGNED_OUT
            }
            return FileOrigin(
                release = release?.title ?: listing?.title,
                fileName = name ?: stored?.name,
                fileSize = size ?: stored?.size,
                indexer = indexer,
                providers = if (fromService) emptyList() else providers.map { it.name.ifBlank { it.host } },
                connections = connections.takeUnless { fromService },
                service = service,
            )
        }
    }
}

/**
 * What File details list, kept apart from the panel so it can be tested, as [TrackSheet] is: the release and its file,
 * then File, Video, On this device and Source, in the approved mockups' order and words, a fact to a row ("Frame rate",
 * "23.976 fps") with a note under it where the mockups have one. A fact not known is left out, never said to be
 * unknown. Until the player knows the file's tracks ([Playing.tracksKnown]), Video and On this device say the file is
 * being read, unless the play has stopped; the rest shows from the launch at once.
 *
 * Where the release's name claims what the file states otherwise for certain (its resolution, codec, bit depth, Dolby
 * Vision, HDR), the row says so (W4). HDR10+ is never contradicted: Media3 1.11.1 doesn't read it from an HEVC stream,
 * so the player can't say whether the file has it. Nothing about audio or subtitles: the audio and subtitles sheet has
 * them.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class FileDetails(origin: FileOrigin, playing: Playing = Playing()) {
    /**
     * What the player knows of the file: whether it has read its tracks, the [video] track (the one selected, else the
     * first, which a device that plays none still has), the file's length, the [decoder] it started and the [display];
     * and whether the play has [stopped] for good, after which nothing more is read.
     */
    data class Playing(
        val tracksKnown: Boolean = false,
        val video: Format? = null,
        val durationMs: Long? = null,
        val decoder: Fact? = null,
        val display: Fact? = null,
        val stopped: Boolean = false,
    )

    /**
     * A fact: its [label], [value] and [note], and what the name [nameSays] otherwise. The HDR row's types show as chips
     * ([hdr]); its value names them for TalkBack.
     */
    data class Row(val label: String, val value: String, val note: String? = null, val nameSays: List<String> = emptyList(), val hdr: List<Hdr> = emptyList())

    /** A section of rows; while [reading], one line saying the file is being read instead. */
    data class Section(val title: String, val rows: List<Row>, val reading: Boolean = false)

    val release: String? = origin.release
    val fileName: String? = origin.fileName

    val sections: List<Section>

    init {
        val video = playing.video?.let(VideoDetails::of)
        val claims = origin.release?.let(ReleaseParser::parse)
        val duration = playing.durationMs?.takeIf { it > 0 }
        val container = playing.video?.containerMimeType?.let(CONTAINERS::get)
            ?: origin.fileName?.let(UsenetDataSource::containerMimeType)?.let(CONTAINERS::get)
        val file = listOfNotNull(
            container?.let { Row("Container", it) },
            origin.fileSize?.let { Row("Size", formatSize(it)) },
            origin.fileSize?.let { size -> duration?.let { Row("Average bitrate", bitrate(size * 8_000 / it), "the file’s size over its length") } },
            duration?.let { Row("Length", length(it)) },
        )
        val videoRows = video?.let { v ->
            fun says(claim: Any?, stated: Any?, words: String?) = listOfNotNull(words?.takeIf { claim != null && stated != null && claim != stated }?.let { "The name says $it" })
            // SD names are loose: a film cropped in a 480p encode is stored 852x356, its class only that height, and DVD's
            // 720 wide is anamorphic. Under 720p name and file can't be told to disagree.
            val resolution = claims?.resolution?.takeUnless { it < HD && (v.resolutionClass ?: HD) < HD }
            val dolbyVisionRead = playing.video?.containerMimeType in DOLBY_VISION_READ
            listOfNotNull(
                v.format?.let { Row("Format", it.value, it.note, says(claims?.videoCodec, v.codec, claims?.videoCodec?.label)) },
                v.resolution?.let { Row("Resolution", it.value, it.note, says(resolution, v.resolutionClass, resolution?.let { r -> "${r}p" })) },
                v.frameRate?.let { Row("Frame rate", it.value, it.note) },
                v.bitDepth?.let { Row("Bit depth", it.value, it.note, says(claims?.bitDepth, v.bits, claims?.bitDepth?.let { b -> "$b-bit" })) },
                Row("HDR", v.hdr.types.joinToString { it.label }.ifEmpty { "None" }, v.hdr.note, hdrClaims(claims?.hdr.orEmpty(), v.hdr.types, dolbyVisionRead), v.hdr.types),
                v.colour?.let { Row("Colour", it.value, it.note) },
            )
        }.orEmpty()
        val device = listOfNotNull(playing.decoder?.let { Row("Decoder", it.value, it.note) }, playing.display?.let { Row("Display", it.value, it.note) })
        val source = listOfNotNull(
            origin.indexer?.let { Row("Indexer", it) },
            origin.service?.let { Row("Service", it, "a cached torrent, streamed from the service") },
            origin.providers.takeIf { it.isNotEmpty() }?.let { names ->
                val connections = origin.connections?.let { if (it == 1) "1 connection" else "$it connections" }
                Row("Providers", names.joinToString(", then "), listOfNotNull("in this order".takeIf { names.size > 1 }, connections).joinToString(" · ").ifEmpty { null })
            },
        )
        // Once the play has stopped nothing more is read: what wasn't is left out, as unknown.
        val reading = !playing.tracksKnown && !playing.stopped
        sections = listOf(
            Section("File", file),
            Section("Video", videoRows, reading),
            Section("On this device", device, reading),
            Section("Source", source),
        ).filter { it.reading || it.rows.isNotEmpty() }.map { if (it.reading) it.copy(rows = emptyList()) else it }
    }

    private companion object {
        /** The lowest resolution class an HD file or name has. */
        const val HD = 720

        /** Containers by their MIME type, as the extractor or the file's extension names them. */
        val CONTAINERS = mapOf(
            MimeTypes.VIDEO_MATROSKA to "Matroska (MKV)", MimeTypes.VIDEO_WEBM to "WebM", MimeTypes.VIDEO_MP4 to "MP4", MimeTypes.VIDEO_MP2T to "MPEG-TS",
            MimeTypes.VIDEO_AVI to "AVI", MimeTypes.VIDEO_QUICK_TIME to "QuickTime (MOV)", MimeTypes.VIDEO_PS to "MPEG-PS", "video/x-ms-wmv" to "WMV",
        )

        /**
         * The containers Media3 1.11.1 reads Dolby Vision in, from Matroska's and MP4's records: its MPEG-TS reader plays a
         * Dolby Vision stream as the HEVC under it, so a .ts or .m2ts file can't be said to have none.
         */
        val DOLBY_VISION_READ = setOf(MimeTypes.VIDEO_MATROSKA, MimeTypes.VIDEO_WEBM, MimeTypes.VIDEO_MP4)

        /**
         * What the name claims of HDR that the file's video shows otherwise: Dolby Vision it hasn't, where its container
         * would show it ([dolbyVisionRead]), or HDR10 or HLG in a file without HDR. A name's HDR10+ is only said to be
         * unconfirmed: in a file with PQ it may be there or not. The parser reads the "HDR10" in "HDR10+" as an HDR10 claim
         * too, so beside HDR10+ that one isn't counted: "HDR10+" and "HDR10Plus" read the same.
         */
        fun hdrClaims(claimed: Set<Hdr>, shown: List<Hdr>, dolbyVisionRead: Boolean): List<String> {
            val plain = if (Hdr.Hdr10Plus in claimed) listOf(Hdr.Hlg) else listOf(Hdr.Hdr10, Hdr.Hlg)
            val missing = listOfNotNull(Hdr.DolbyVision.takeIf { it in claimed && it !in shown && dolbyVisionRead }) +
                plain.filter { it in claimed && shown.isEmpty() }
            return listOfNotNull(
                missing.takeIf { it.isNotEmpty() }?.let { types -> "The name says ${types.joinToString(" and ") { it.label }}; the file has none" },
                "The name says HDR10+; the player can't confirm it".takeIf { Hdr.Hdr10Plus in claimed },
            )
        }

        /** "23.4 Mbps", whole from 100; "< 0.1 Mbps" for less, as the release list's chip says it. Rounded half up, in the locale's digits. */
        fun bitrate(bitsPerSecond: Long): String {
            val tenths = (bitsPerSecond + 50_000) / 100_000
            return when {
                tenths == 0L -> "< 0.1 Mbps"
                tenths < 1_000 -> "%.1f Mbps".format(tenths / 10.0)
                else -> "%d Mbps".format((bitsPerSecond + 500_000) / 1_000_000)
            }
        }

        /** "48:00", "1:54:10", as the player's controls show a length. */
        fun length(ms: Long): String {
            val s = ms / 1000
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/** Which section of File details a node is, by its title: for tests, which find the one a remote's focus is on. */
val FileDetailsSection = SemanticsPropertyKey<String>("FileDetailsSection")
private var SemanticsPropertyReceiver.fileDetailsSection by FileDetailsSection

/**
 * File details, a [SidePanel] over the player: the release and its file, then what [details] says of the file, the video,
 * this device and where it came from, in the approved mockups' look. It only reads, so each section takes a remote's
 * focus whole (opening on the first, in the remote's input mode), and Down and Up go a section at a time, bringing each
 * into view; the focus shows as the app's elsewhere ([focusOutline]), in a TV's cream there. Touch scrolls it. For
 * TalkBack each section's title is a heading and each row is read as one: label, value, note.
 */
@Composable
fun FileDetailsPanel(details: FileDetails, onClose: () -> Unit, safeArea: WindowInsets) {
    val first = remember { FocusRequester() }
    val keys = LocalInputModeManager.current
    LaunchedEffect(Unit) { if (keys.inputMode == InputMode.Keyboard) first.tryFocus() }
    SidePanel("File details", onClose, safeArea) {
        val release = details.release != null || details.fileName != null
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Each section keyed by its title, so one that drops out as the facts come (Video, for a file whose video the
            // player doesn't read) takes nothing of the others' with it: not the remote's focus on a later one.
            if (release) {
                key("Release") {
                    DetailsSection("Release", first) {
                        details.release?.let { Text(it, style = AppType.release, color = colors.text) }
                        details.fileName?.let { Text(it, style = AppType.release, color = colors.muted, modifier = Modifier.padding(top = 6.dp)) }
                    }
                }
            }
            details.sections.forEachIndexed { i, section ->
                key(section.title) {
                    DetailsSection(section.title, first.takeIf { !release && i == 0 }) {
                        if (section.reading) Text("Reading the file…", style = AppType.body, color = colors.textSecondary)
                        section.rows.forEach { FactRow(it.label, it.value, it.note, it.nameSays, it.hdr) }
                    }
                }
            }
        }
    }
}

/**
 * A section: its title in capitals, then [content]; one stop for a remote's focus, grown a little and ringed on a TV
 * (FdTvOpen). One taller than the panel's room (the video's, at a large font size on a TV) reads a page at a time: Down
 * and Up scroll through it first, then go on to the next section, so no row in its middle is passed over.
 */
@Composable
private fun DetailsSection(title: String, focus: FocusRequester?, content: @Composable ColumnScope.() -> Unit) {
    val tv = LocalFormFactor.current == FormFactor.Tv
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .semantics { fileDetailsSection = title }
            .pagesThrough(LocalSidePanelRoom.current)
            .focusScale(interaction, { it.rowScale })
            .focusOutline(color = if (tv) colors.text else null)
            // Focusable without merging: TalkBack passes over the section to its heading and rows (each a node of its own).
            .focusable(interactionSource = interaction)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
    ) {
        Text(title.uppercase(), style = AppType.section, color = colors.muted, modifier = Modifier.padding(bottom = 4.dp).semantics { heading() })
        content()
    }
}
