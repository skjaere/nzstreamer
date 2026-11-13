package io.skjaere.usenet.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.AvcConfig
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.HevcConfig
import androidx.media3.extractor.mkv.EbmlProcessor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/**
 * Media3's extractors, Matroska's reading the track flags Media3's own skips ([FlaggedMatroskaExtractor]).
 * The rest are DefaultExtractorsFactory's; [matroskaFlags] are its Matroska flags (the probe's FLAG_DISABLE_SEEK_FOR_CUES).
 * Each goes past a stretch of the file no provider has, where the file's index allows ([GapSkippingExtractor]), telling
 * the play's [gaps] of it.
 */
@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
internal class TrackFlagsExtractorsFactory(private val matroskaFlags: Int = 0, private val gaps: PlayGaps? = null) : ExtractorsFactory {
    private val default = DefaultExtractorsFactory().setMatroskaExtractorFlags(matroskaFlags)

    // What the media source sets, kept to build Matroska's extractor as the default factory would.
    private var subtitleParserFactory: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var textTrackTranscoding = true
    private var parseHagcMetadata = true

    @Synchronized
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
        default.setSubtitleParserFactory(subtitleParserFactory)
        this.subtitleParserFactory = subtitleParserFactory
    }

    @Deprecated("Media3's legacy subtitle path, which DefaultMediaSourceFactory still sets")
    @Synchronized
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
        @Suppress("DEPRECATION")
        default.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        textTrackTranscoding = textTrackTranscodingEnabled
    }

    @Synchronized
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory = apply {
        default.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
    }

    @Synchronized
    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory = apply {
        default.setParseHagcMetadata(parseHagcMetadata)
        this.parseHagcMetadata = parseHagcMetadata
    }

    @Synchronized
    override fun createExtractors(): Array<Extractor> = default.createExtractors().map(::forPlay).toTypedArray()

    @Synchronized
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        default.createExtractors(uri, responseHeaders).map(::forPlay).toTypedArray()

    /** Matroska's extractor with the arguments DefaultExtractorsFactory gives its own. */
    @Synchronized
    internal fun matroska(): Extractor = FlaggedMatroskaExtractor(
        subtitleParserFactory,
        matroskaFlags or
            (if (textTrackTranscoding) 0 else MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA) or
            (if (parseHagcMetadata) 0 else MatroskaExtractor.FLAG_DISABLE_HAGC_METADATA),
    )

    /** [extractor], one of DefaultExtractorsFactory's, as a play uses it: Matroska's in its place, each skipping missing stretches. */
    internal fun forPlay(extractor: Extractor): Extractor =
        GapSkippingExtractor(if (extractor.javaClass == MatroskaExtractor::class.java) matroska() else extractor, gaps)
}

/**
 * Media3's Matroska extractor, but that a track's FlagCommentary, FlagHearingImpaired,
 * FlagVisualImpaired and FlagOriginal reach its Format's roleFlags: Media3 1.11.1 reads only
 * FlagDefault and FlagForced (#153), so a track marked only by a flag looked like the main one.
 * A flag set to 0 adds nothing. A video track's format also carries what File details show and
 * Media3 reads but drops ([MatroskaVideoFacts]).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class FlaggedMatroskaExtractor(subtitleParserFactory: SubtitleParser.Factory, flags: Int) : MatroskaExtractor(subtitleParserFactory, flags) {
    // The flags of the TrackEntry being read: its children all come before its end, where Media3 builds its format.
    private var roleFlags = 0

    // Its MaxCLL, if it has one: Media3 starts each track at a default it can't be told from.
    private var maxCll: Int? = null

    override fun getElementType(id: Int): Int = if (id in ROLES) EbmlProcessor.ELEMENT_TYPE_UNSIGNED_INT else super.getElementType(id)

    override fun startMasterElement(id: Int, contentPosition: Long, contentSize: Long) {
        if (id == TRACK_ENTRY) {
            roleFlags = 0
            maxCll = null
        }
        super.startMasterElement(id, contentPosition, contentSize)
    }

    override fun integerElement(id: Int, value: Long) {
        // Media3 reads it as well.
        if (id == MAX_CLL) maxCll = value.toInt()
        val role = ROLES[id] ?: return super.integerElement(id, value)
        if (value == 1L) roleFlags = roleFlags or role
    }

    override fun endMasterElement(id: Int) {
        val track = if (id == TRACK_ENTRY) getCurrentTrack(id) else null
        super.endMasterElement(id)
        // The track's output gets this format once all the tracks (or the Cues) are read, and later
        // updates build on it: Media3 appends the file's chapters to its metadata. A codec Media3
        // doesn't play has none.
        val format = track?.format ?: return
        val facts = videoFacts(track, format)
        if (roleFlags == 0 && facts == null) return
        track.format = format.buildUpon()
            .setRoleFlags(format.roleFlags or roleFlags)
            .apply { if (facts != null) setMetadata(format.metadata?.copyWithAppendedEntries(facts) ?: Metadata(facts)) }
            .build()
    }

    /** What [track] says beyond its [format] that File details show; null for a track that isn't video, or says nothing more. */
    private fun videoFacts(track: Track, format: Format): MatroskaVideoFacts? {
        if (!MimeTypes.isVideo(format.sampleMimeType)) return null
        // Media3 makes a track Dolby Vision only for a record its DolbyVisionConfig reads.
        val dolbyVision = format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        // Media3 marks the record's and CodecPrivate's fields never null once set; they are null until then.
        val facts = MatroskaVideoFacts(
            frameDurationNs = track.defaultSampleDurationNs.takeIf { it > 0 },
            dolbyVision = if (dolbyVision) DolbyVisionRecord.parse(track.dolbyVisionConfigBytes) else null,
            baseCodecs = if (dolbyVision) baseCodecs(track.codecId, track.codecPrivate) else null,
            maxCll = maxCll,
        )
        return facts.takeIf { it != MatroskaVideoFacts() }
    }

    /** The codecs Media3 named [codecId]'s codec by from its CodecPrivate ([config]), before a Dolby Vision record's replaced them. */
    private fun baseCodecs(codecId: String?, config: ByteArray?): String? {
        config ?: return null
        return try {
            when (codecId) {
                "V_MPEGH/ISO/HEVC" -> HevcConfig.parse(ParsableByteArray(config)).codecs
                "V_MPEG4/ISO/AVC" -> AvcConfig.parse(ParsableByteArray(config)).codecs
                else -> null
            }
        } catch (e: ParserException) {
            // Media3 read it to build the format, so this doesn't happen; the name is only a label.
            null
        }
    }

    private companion object {
        const val TRACK_ENTRY = 0xAE
        const val MAX_CLL = 0x55BC

        /** Matroska's flags by element id, as Media3's role flags. */
        val ROLES = mapOf(
            0x55AF to C.ROLE_FLAG_COMMENTARY, // FlagCommentary
            0x55AB to C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND, // FlagHearingImpaired: SDH, for subtitles and audio alike
            0x55AC to C.ROLE_FLAG_DESCRIBES_VIDEO, // FlagVisualImpaired: audio description
            0x55AE to C.ROLE_FLAG_MAIN, // FlagOriginal: the original language's track
        )
    }
}
