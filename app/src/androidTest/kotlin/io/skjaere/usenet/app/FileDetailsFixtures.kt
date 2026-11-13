package io.skjaere.usenet.app

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import io.skjaere.usenet.app.play.FileDetails
import io.skjaere.usenet.app.play.FileOrigin
import io.skjaere.usenet.player.DecoderDetails
import io.skjaere.usenet.player.DisplayDetails
import io.skjaere.usenet.player.DisplayFacts
import io.skjaere.usenet.player.DolbyVisionRecord
import io.skjaere.usenet.player.ListedDecoder
import io.skjaere.usenet.player.MatroskaVideoFacts
import io.skjaere.usenet.player.ModeSize
import io.skjaere.usenet.releases.Hdr

/**
 * File details as a Shield playing a 4K Dolby Vision profile 8.1 film would have them, as measured on one: its
 * DV decoder, its 4K output at 59.94 Hz showing Dolby Vision, HDR10 and HLG. The emulators show none of it, so the
 * panel's tests make it up. The release is invented, with a long name that claims HDR10+.
 */
object ShieldFile {
    const val RELEASE = "Kestrel.Point.2026.2160p.UHD.BluRay.REMUX.DV.HDR10Plus.HEVC.TrueHD.7.1.Atmos-NZST"
    const val FILE = "Kestrel.Point.2026.2160p.UHD.BluRay.REMUX.DV.HDR10Plus.HEVC.TrueHD.7.1.Atmos-NZST.mkv"

    /** Dolby Vision profile 8.1 at level 6 over HEVC Main 10, 23.976 fps, with HDR10's mastering display and MaxCLL. */
    val video: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.08.06").setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
        .setWidth(3840).setHeight(1606)
        .setColorInfo(
            ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084).setColorRange(C.COLOR_RANGE_LIMITED)
                .setLumaBitdepth(10).setChromaBitdepth(10).build(),
        )
        .setMetadata(Metadata(MatroskaVideoFacts(41_708_333, DolbyVisionRecord(compatibility = 1, enhancementLayer = false, baseLayer = true), "hvc1.2.4.H153.90")))
        .build()

    private val display = DisplayDetails(
        DisplayFacts(tv = true, output = ModeSize(3840, 2160), fromTv = true, largest = ModeSize(3840, 2160), window = ModeSize(1920, 1080), hdr = setOf(Hdr.DolbyVision, Hdr.Hdr10, Hdr.Hlg)),
        59.94f,
    )

    val origin = FileOrigin(
        release = RELEASE, fileName = FILE, fileSize = 61_234_567_890, indexer = "My indexer",
        providers = listOf("news.example.net", "fill.example.org"), connections = 6,
    )

    val playing = FileDetails.Playing(
        tracksKnown = true, video = video, durationMs = (128 * 60 + 41) * 1000L,
        decoder = DecoderDetails.of(
            "OMX.Nvidia.DOVI.decode", video,
            { mime -> if (mime == MimeTypes.VIDEO_DOLBY_VISION) listOf(ListedDecoder("OMX.Nvidia.DOVI.decode", hardware = true)) else emptyList() },
            displayDolbyVision = true,
        ),
        display = display.fact,
    )

    val details = FileDetails(origin, playing)

    /** The same film from a debrid service (L207): its torrent indexer, then the service in place of the providers. */
    val fromService = FileDetails(origin.copy(indexer = "Home Prowlarr", providers = emptyList(), connections = null, service = "Premiumize"), playing)

    /** As the panel opens on a play whose tracks the player hasn't read yet. */
    val reading = FileDetails(origin)

    /** The sections' titles as the panel shows them, Release first. */
    val sections = listOf("Release", "File", "Video", "On this device", "Source")
}
