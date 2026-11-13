package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks

/** Formats and track lists as a player reports them for a Matroska file, built on the JVM. */
internal object TrackFixtures {
    fun audio(
        id: String,
        language: String?,
        mime: String = MimeTypes.AUDIO_AAC,
        channels: Int = 2,
        label: String? = null,
        selection: Int = 0,
        roles: Int = 0,
    ): Format = Format.Builder().setId(id).setSampleMimeType(mime).setLanguage(language).setChannelCount(channels)
        .setLabel(label).setSelectionFlags(selection).setRoleFlags(roles).build()

    /** A subtitle track in its own format, as a play's player reports PGS and VobSub: their samples left to the text renderer (PlaySubtitleParserFactory). */
    fun text(id: String, language: String?, mime: String = MimeTypes.APPLICATION_SUBRIP, label: String? = null, selection: Int = 0, roles: Int = 0): Format =
        Format.Builder().setId(id).setSampleMimeType(mime).setLanguage(language)
            .setLabel(label).setSelectionFlags(selection).setRoleFlags(roles).build()

    /**
     * A subtitle track's [format] as Media3 reports it when it parses subtitles while extracting (its default, the track
     * probe's, and a play's for all but PGS and VobSub): its own format in codecs.
     */
    fun parsedWhileExtracting(format: Format): Format =
        format.buildUpon().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(format.sampleMimeType).build()

    /** A video track; [bitDepth] as Media3 reads it from the codec's configuration (HEVC's, AV1's), unknown by default. */
    fun video(
        id: String,
        mime: String = MimeTypes.VIDEO_H264,
        codecs: String? = null,
        width: Int = 160,
        height: Int = 90,
        bitDepth: Int = Format.NO_VALUE,
    ): Format = Format.Builder().setId(id).setSampleMimeType(mime).setCodecs(codecs).setWidth(width).setHeight(height)
        .setColorInfo(if (bitDepth == Format.NO_VALUE) null else ColorInfo.Builder().setLumaBitdepth(bitDepth).setChromaBitdepth(bitDepth).build())
        .build()

    /**
     * One track per group, as in a file; [selected] by format id, [unsupported] can't be played. [support] rates others as
     * the renderers would (a C.FORMAT_* value by format id), by default as handled.
     */
    fun tracks(
        formats: List<Format>,
        selected: Set<String> = emptySet(),
        unsupported: Set<String> = emptySet(),
        groupIdPrefix: String = "0",
        support: Map<String, Int> = emptyMap(),
    ): Tracks =
        Tracks(
            formats.mapIndexed { i, f ->
                Tracks.Group(
                    TrackGroup("$groupIdPrefix:$i", f), false,
                    intArrayOf(support[f.id] ?: if (f.id in unsupported) C.FORMAT_UNSUPPORTED_SUBTYPE else C.FORMAT_HANDLED),
                    booleanArrayOf(f.id in selected),
                )
            },
        )

    // The device test's fixture: invented tracks, its commentary flagged default as some files do.
    val englishStereo = audio("1", "en")
    val german51 = audio("2", "de", channels = 6)
    val commentary = audio("3", "en", label = "Commentary", selection = C.SELECTION_FLAG_DEFAULT)
    val frenchEac3 = audio("4", "fr", MimeTypes.AUDIO_E_AC3, channels = 6)
    val englishSubs = text("5", "en")
    val englishSdh = text("6", "en", label = "SDH")
    val germanForced = text("7", "de", selection = C.SELECTION_FLAG_FORCED)
    val germanSubs = text("8", "de")

    val fixture = listOf(video("0"), englishStereo, german51, commentary, frenchEac3, englishSubs, englishSdh, germanForced, germanSubs)

    fun fixtureTracks(selected: Set<String> = setOf("0", "3"), groupIdPrefix: String = "0") =
        tracks(fixture, selected, unsupported = setOf("4"), groupIdPrefix = groupIdPrefix)
}
