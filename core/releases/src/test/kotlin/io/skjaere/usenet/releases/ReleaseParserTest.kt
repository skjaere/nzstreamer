package io.skjaere.usenet.releases

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.LocalDate
import java.util.Locale
import kotlin.test.assertEquals

class ReleaseParserTest {

    private val fields: Map<String, (ParsedRelease) -> Any?> = mapOf(
        "title" to { it.title }, "year" to { it.year }, "seasons" to { it.seasons }, "episodes" to { it.episodes },
        "airDate" to { it.airDate }, "resolution" to { it.resolution }, "source" to { it.source },
        "video" to { it.videoCodec }, "bitDepth" to { it.bitDepth }, "hdr" to { it.hdr }, "audio" to { it.audio },
        "atmos" to { it.atmos }, "channels" to { it.channels }, "languages" to { it.languages },
        "dl" to { it.dualLanguage }, "multi" to { it.multiLanguage }, "edition" to { it.edition },
        "proper" to { it.proper }, "disc" to { it.discImage }, "group" to { it.group }, "pack" to { it.isSeasonPack },
        "tenBit" to { it.tenBit }, "dvOnly" to { it.dolbyVisionOnly }, "key" to { it.groupKey },
    )

    private fun case(name: String, vararg expected: Pair<String, Any?>) = DynamicTest.dynamicTest(name) {
        val parsed = ReleaseParser.parse(name)
        expected.forEach { (field, value) ->
            assertEquals(value, fields.getValue(field)(parsed), "$field of $name (parsed: $parsed)")
        }
    }

    @TestFactory
    fun episodes() = listOf(
        case("Harbor.Lights.S01E02.1080p.WEB-DL.DDP5.1.H.264-NTGRP",
            "title" to "Harbor Lights", "year" to null, "seasons" to listOf(1), "episodes" to listOf(2),
            "resolution" to 1080, "source" to Source.WebDl, "video" to VideoCodec.H264,
            "audio" to listOf(AudioCodec.Eac3), "channels" to "5.1", "group" to "NTGRP", "pack" to false,
            "key" to "harbor lights s01e02"),
        case("The.Quiet.Orbit.S02E10.German.DL.720p.WEB.h264-ZZGER",
            "title" to "The Quiet Orbit", "seasons" to listOf(2), "episodes" to listOf(10), "resolution" to 720,
            "source" to Source.WebDl, "languages" to setOf("de", "en"), "dl" to true, "group" to "ZZGER"),
        case("Copper Valley S03E04E05 720p HDTV x264-SANDBAR",
            "title" to "Copper Valley", "episodes" to listOf(4, 5), "source" to Source.Hdtv, "group" to "SANDBAR"),
        case("Copper.Valley.S03E04-E06.1080p.WEBRip.x265-GRP",
            "episodes" to listOf(4, 5, 6), "source" to Source.WebRip, "video" to VideoCodec.Hevc),
        case("Copper.Valley.S03E04-06.1080p.AMZN.WEB-DL.DDP2.0.H.264-GRP",
            "episodes" to listOf(4, 5, 6), "source" to Source.WebDl, "channels" to "2.0"),
        case("Northbound.3x07.HDTV.XviD-XVGRP",
            "title" to "Northbound", "seasons" to listOf(3), "episodes" to listOf(7), "video" to VideoCodec.Xvid),
        case("Salt.and.Signal.2019.S01E01.REPACK.1080p.NF.WEB-DL.DDP5.1.Atmos.HEVC-GRP",
            "title" to "Salt and Signal", "year" to 2019, "proper" to true, "atmos" to true, "video" to VideoCodec.Hevc,
            "key" to "salt and signal (2019) s01e01"),
        case("Glasshouse.US.S05E03.2160p.WEB.H265.HDR10+.DV-GRP",
            "title" to "Glasshouse US", "resolution" to 2160, "hdr" to setOf(Hdr.Hdr10, Hdr.Hdr10Plus, Hdr.DolbyVision),
            "tenBit" to true, "dvOnly" to false),
        case("Glasshouse.S05E03.2160p.WEB.H265.DV-GRP", "hdr" to setOf(Hdr.DolbyVision), "dvOnly" to true),
        case("Morning.Ledger.2024.03.05.720p.WEB.h264-GRP",
            "title" to "Morning Ledger", "airDate" to LocalDate.of(2024, 3, 5), "year" to null,
            "key" to "morning ledger 2024-03-05"),
        case("Far.Shore.S01E1024.1080p.WEB.x264-GRP", "episodes" to listOf(1024)),
        case("harbor_lights_s01e02_720p_hdtv_x264-grp", "title" to "harbor lights", "episodes" to listOf(2), "resolution" to 720),
        case("Harbor Lights - S01E02 - Tide Pools [1080p] [WEB-DL]", "title" to "Harbor Lights", "resolution" to 1080, "group" to null),
    )

    /**
     * L178: torrent names (and some NZBs') carry a site's tag before the name and brackets after it; a tag and every
     * trailing bracket holding nothing the parser reads go before parsing, and a bracket holding a quality token stays
     * (review finding #58: "[x265]" was dropped as the one trailing bracket).
     */
    @TestFactory
    fun torrentNames() = listOf(
        case("www.Example.org - Quiet Orbit S02E05 1080p WEB H264-GRP",
            "title" to "Quiet Orbit", "seasons" to listOf(2), "episodes" to listOf(5), "resolution" to 1080, "source" to Source.WebDl,
            "video" to VideoCodec.H264, "group" to "GRP", "key" to "quiet orbit s02e05"),
        case("[ www.example.org ] Quiet.Orbit.S02E05.1080p.WEB.H264-GRP",
            "title" to "Quiet Orbit", "seasons" to listOf(2), "episodes" to listOf(5), "video" to VideoCodec.H264, "group" to "GRP"),
        case("[www.Example.org] - Quiet.Orbit.S02E05.720p.HDTV.x264-GRP", "title" to "Quiet Orbit", "resolution" to 720, "source" to Source.Hdtv),
        case("Quiet Orbit (2019) [1080p] [WEB-DL] [x265]",
            "title" to "Quiet Orbit", "year" to 2019, "resolution" to 1080, "source" to Source.WebDl, "video" to VideoCodec.Hevc),
        case("Quiet Orbit (2019) [1080p] [WEB-DL] [x265] [siteA] (SiteB)", "title" to "Quiet Orbit", "year" to 2019, "video" to VideoCodec.Hevc),
        case("Quiet.Orbit.S02E05.1080p.WEB.H264-GRP[siteA]", "title" to "Quiet Orbit", "group" to "GRP", "video" to VideoCodec.H264),
        case("Quiet.Orbit.S02E05.1080p.WEB.H264-GRP [siteC] [SiteB]", "title" to "Quiet Orbit", "group" to "GRP"),
        case("Quiet Orbit (2019) [2160p HDR]", "title" to "Quiet Orbit", "year" to 2019, "resolution" to 2160, "hdr" to setOf(Hdr.Hdr10)),
        // A year and an episode in brackets are read, so they stay.
        case("Copper Crown (2019)", "title" to "Copper Crown", "year" to 2019),
        case("Quiet Orbit [S02E05] [siteA]", "title" to "Quiet Orbit", "seasons" to listOf(2), "episodes" to listOf(5)),
        case("www.Example.co.uk - Quiet Orbit S02E05 1080p WEB H264-GRP", "title" to "Quiet Orbit", "seasons" to listOf(2), "episodes" to listOf(5)),
        // DDR-4: an address run into a dotted name isn't a site's tag, however the name ends: the name is read as it was.
        case("www.Example.org.Quiet.Orbit.S02E05.1080p.WEB.H264-GRP [siteA]",
            "seasons" to listOf(2), "episodes" to listOf(5), "resolution" to 1080, "source" to Source.WebDl, "group" to "GRP"),
        case("www.Example.org.Copper.Crown.2019.1080p.BluRay.x264-GRP (SiteB)",
            "year" to 2019, "resolution" to 1080, "source" to Source.BluRay, "video" to VideoCodec.H264, "group" to "GRP"),
    )

    @TestFactory
    fun seasonPacks() = listOf(
        case("Harbor.Lights.S01.1080p.BluRay.x264-GRP",
            "seasons" to listOf(1), "episodes" to emptyList<Int>(), "pack" to true, "source" to Source.BluRay,
            "key" to "harbor lights s01"),
        case("Harbor.Lights.S01-S03.German.DL.720p.WEB.x264-GRP", "seasons" to listOf(1, 2, 3), "pack" to true),
        // A range without its second S, and seasons listed one after another, are several seasons too.
        case("Harbor.Lights.S01-03.1080p.BluRay.x264-GRP", "title" to "Harbor Lights", "seasons" to listOf(1, 2, 3), "pack" to true, "resolution" to 1080),
        case("Harbor.Lights.S01.S02.S03.1080p.WEB-DL.x264-GRP", "title" to "Harbor Lights", "seasons" to listOf(1, 2, 3), "pack" to true, "resolution" to 1080),
        // With spaces about the dash and no second S, it is how some name an episode ("S2 - 04"): not read as a range.
        case("Harbor Lights S2 - 04 720p WEB", "title" to "Harbor Lights", "seasons" to listOf(2)),
        case("Harbor Lights Season 2 Complete 1080p WEB-DL", "title" to "Harbor Lights", "seasons" to listOf(2), "pack" to true),
        case("Harbor.Lights.COMPLETE.SERIES.720p.WEB", "title" to "Harbor Lights", "pack" to false),
    )

    @TestFactory
    fun movies() = listOf(
        case("Copper.Crown.2019.1080p.BluRay.x264.DTS-HD.MA.5.1-GRP",
            "title" to "Copper Crown", "year" to 2019, "source" to Source.BluRay, "audio" to listOf(AudioCodec.DtsHdMa),
            "channels" to "5.1", "group" to "GRP", "key" to "copper crown (2019)"),
        case("Copper.Crown.2019.German.DL.1080p.BluRay.AVC.REMUX-GRP",
            "source" to Source.Remux, "video" to VideoCodec.H264, "languages" to setOf("de", "en")),
        case("Copper.Crown.2019.German.AC3D.DL.1080p.BluRay.x264-GRP",
            "audio" to listOf(AudioCodec.Ac3), "languages" to setOf("de", "en")),
        case("1999.2021.1080p.WEB-DL.H264-GRP", "title" to "1999", "year" to 2021),
        case("Island.of.2044.2010.720p.BluRay.x264-GRP", "title" to "Island of 2044", "year" to 2010),
        case("Copper Crown (2019) 2160p UHD BluRay TrueHD 7.1 Atmos HEVC 10bit HDR",
            "title" to "Copper Crown", "year" to 2019, "resolution" to 2160, "audio" to listOf(AudioCodec.TrueHd),
            "channels" to "7.1", "atmos" to true, "bitDepth" to 10, "hdr" to setOf(Hdr.Hdr10), "group" to null),
        case("Copper.Crown.2019.COMPLETE.UHD.BLURAY-GRP", "disc" to true),
        case("Copper.Crown.2019.1080p.BluRay.AVC.BDMV-GRP", "disc" to true),
        case("Copper.Crown.2019.Extended.Cut.1080p.BluRay.x264-GRP", "edition" to "Extended"),
        case("Copper.Crown.2019.Directors.Cut.MULTi.1080p.WEB.x264-GRP", "edition" to "Director's Cut", "multi" to true),
        case("Copper.Crown.2019.FRENCH.1080p.WEB.H264-GRP", "languages" to setOf("fr")),
        case("Copper.Crown.2019.HDCAM.x264-GRP", "source" to Source.Cam),
        case("Copper.Crown.2019.720p.BluRay.Hi10P.x264-GRP", "bitDepth" to 10, "tenBit" to true),
        case("Copper.Crown.2019.1080p.WEB.AV1.Opus.5.1-GRP", "video" to VideoCodec.Av1, "audio" to listOf(AudioCodec.Opus), "channels" to "5.1"),
        case("Copper.Crown.2019.1080p.WEB-DL", "group" to null, "source" to Source.WebDl),
        case("Copper.Crown.2019.1080p.BluRay.E-AC-3.x264-GRP.nzb", "audio" to listOf(AudioCodec.Eac3), "group" to "GRP"),
        case("Cam.2011.1080p.WEB.x264-GRP", "title" to "Cam", "year" to 2011),
        case("Just Some Words", "title" to "Just Some Words", "year" to null, "resolution" to null, "key" to "just some words"),
    )

    /** Language, source, audio and marker words inside a title belong to the title, not to the release. */
    @TestFactory
    fun wordsInTitles() = listOf(
        case("The.Italian.Harbor.2003.1080p.BluRay.x264-GRP",
            "title" to "The Italian Harbor", "year" to 2003, "languages" to emptySet<String>(), "source" to Source.BluRay),
        case("Captain.English.2003.720p.WEB-DL.AAC2.0.H.264-GRP",
            "title" to "Captain English", "year" to 2003, "languages" to emptySet<String>(), "source" to Source.WebDl,
            "audio" to listOf(AudioCodec.Aac), "channels" to "2.0"),
        case("Gull's.Web.2006.1080p.BluRay.x264-GRP",
            "title" to "Gull's Web", "year" to 2006, "source" to Source.BluRay, "key" to "gulls web (2006)"),
        case("The.Spanish.Quay.S01E01.1080p.WEB.H264-GRP",
            "title" to "The Spanish Quay", "seasons" to listOf(1), "episodes" to listOf(1),
            "languages" to emptySet<String>(), "source" to Source.WebDl),
        case("The.French.Lantern.2021.1080p.WEB.H264-GRP",
            "title" to "The French Lantern", "year" to 2021, "languages" to emptySet<String>(), "source" to Source.WebDl),
        case("Mr.Kestrels.Opus.1995.1080p.BluRay.x264-GRP",
            "title" to "Mr Kestrels Opus", "year" to 1995, "audio" to emptyList<AudioCodec>(), "source" to Source.BluRay),
        case("Russian.Lantern.S02E01.1080p.WEB.H264-GRP",
            "title" to "Russian Lantern", "seasons" to listOf(2), "episodes" to listOf(1), "languages" to emptySet<String>()),
        case("Copper.Shell.2.0.2008.1080p.BluRay.x264-GRP",
            "title" to "Copper Shell 2 0", "year" to 2008, "channels" to null),
        case("A.Complete.Harbor.2024.1080p.WEB.H264-GRP", "title" to "A Complete Harbor", "year" to 2024),
        case("The.English.Orchard.1996.1080p.BluRay.x264-GRP",
            "title" to "The English Orchard", "year" to 1996, "languages" to emptySet<String>()),
        case("Uncut.Copper.2019.1080p.WEB.H264-GRP", "title" to "Uncut Copper", "edition" to null),
        case("The.Danish.Tide.2015.German.DL.1080p.BluRay.x264-GRP",
            "title" to "The Danish Tide", "languages" to setOf("de", "en"), "dl" to true),
        // After the year or episode, the same words still describe the release.
        case("Some.Movie.2020.GERMAN.DL.1080p.WEB.H264-GRP",
            "title" to "Some Movie", "languages" to setOf("de", "en"), "source" to Source.WebDl),
        case("Some.Show.S01E01.FRENCH.720p.WEB.x264-GRP", "title" to "Some Show", "languages" to setOf("fr")),
        case("Some.Movie.2019.Extended.1080p.BluRay.Opus.5.1.x264-GRP",
            "edition" to "Extended", "audio" to listOf(AudioCodec.Opus), "channels" to "5.1"),
        // With no year, tags straight before the resolution, source or codec still describe the release.
        case("Northbound.German.DL.1080p.BluRay.x264-GRP",
            "title" to "Northbound", "year" to null, "languages" to setOf("de", "en"), "dl" to true, "key" to "northbound"),
        case("Northbound.German.1080p.BluRay.x264-GRP", "title" to "Northbound", "languages" to setOf("de")),
        case("Brine.Hollow.EXTENDED.German.DL.1080p.BluRay.x264-GRP",
            "title" to "Brine Hollow", "edition" to "Extended", "languages" to setOf("de", "en")),
        case("Lantern.Field.FRENCH.720p.BluRay.x264-GRP", "title" to "Lantern Field", "languages" to setOf("fr")),
        case("Movie.Title.MULTi.1080p.BluRay.x264-GRP", "title" to "Movie Title", "multi" to true),
        case("Movie.Title.WEB.x264-GRP", "title" to "Movie Title", "source" to Source.WebDl),
        case("Wattenland.Wildes.Ufer.German.DOKU.720p.HDTV.x264-GRP",
            "title" to "Wattenland Wildes Ufer", "languages" to setOf("de"), "source" to Source.Hdtv),
        // ...but not before an episode token, and a bare channel layout before them is still a title.
        case("Lantern.English.S01E01.1080p.WEB.H264-GRP", "title" to "Lantern English", "languages" to emptySet<String>()),
        case("Copper.Shell.2.0.1080p.BluRay.x264-GRP", "title" to "Copper Shell 2 0", "channels" to null),
        // With nothing stronger in the name, the first word token still ends the title.
        case("Harbor.Lights.German.DL", "title" to "Harbor Lights", "languages" to setOf("de", "en")),
        case("Harbor.Lights.The.Complete.Series.1080p.BluRay.x264-GRP", "title" to "Harbor Lights", "source" to Source.BluRay),
    )

    /** A language directly followed by a subtitle tag names the subtitles: the audio is the original, as if untagged. */
    @TestFactory
    fun subtitleLanguages() = listOf(
        case("Moonwell.Harbor.2019.German.Subbed.1080p.BluRay.x264-GRP",
            "title" to "Moonwell Harbor", "year" to 2019, "languages" to emptySet<String>(), "dl" to false, "source" to Source.BluRay),
        case("Moonwell.Express.2016.KOREAN.SUBBED.720p.BluRay.x264-GRP", "title" to "Moonwell Express", "languages" to emptySet<String>()),
        case("Northbound.German.Subbed.1080p.BluRay.x264-GRP", "title" to "Northbound", "languages" to emptySet<String>()),
        case("Harbor.Lights.German.Subbed", "title" to "Harbor Lights", "languages" to emptySet<String>()),
        case("Some.Show.S01E01.German.Sub.720p.WEB.x264-GRP", "languages" to emptySet<String>()),
        case("Some.Show.S01E01.FRENCH.ENG.SUBS.720p.WEB.x264-GRP", "languages" to setOf("fr")),
        case("Some.Movie.2019.German.Hardsub.720p.WEB.x264-GRP", "languages" to emptySet<String>()),
        case("Some.Movie.2019.KOREAN.HC.1080p.WEB.x264-GRP", "languages" to emptySet<String>()),
        case("Some.Movie.2019.KOREAN.HC.SUBBED.1080p.WEB.x264-GRP", "languages" to emptySet<String>()),
        case("Copper.Crown.2019.MULTi.SUBS.1080p.BluRay.x264-GRP", "multi" to false),
        // A dub, or a language followed by anything else, is still the audio.
        case("Copper.Crown.2019.German.Dubbed.1080p.BluRay.x264-GRP", "languages" to setOf("de")),
        case("Copper.Crown.2019.German.DL.1080p.BluRay.x264-GRP", "languages" to setOf("de", "en"), "dl" to true),
        case("Copper.Crown.2019.MULTi.1080p.BluRay.x264-GRP", "multi" to true),
        // A bare DUBBED is the scene's English dub of a foreign title; a stated language or a German dub tag says otherwise.
        case("Moonwell.Village.2021.DUBBED.1080p.BluRay.x264-GRP", "title" to "Moonwell Village", "languages" to setOf("en"), "dl" to false),
        case("Harbor.Lights.S01E02.DUBBED.1080p.WEB.H264-GRP", "episodes" to listOf(2), "languages" to setOf("en")),
        case("Copper.Crown.2019.DUBBED.AC3D.1080p.BluRay.x264-GRP", "languages" to setOf("de")),
        case("Copper.Crown.2019.MULTi.DUBBED.1080p.BluRay.x264-GRP", "languages" to emptySet<String>(), "multi" to true),
        case("The.Dubbed.Life.2020.1080p.WEB.H264-GRP", "title" to "The Dubbed Life", "languages" to emptySet<String>()),
    )

    @TestFactory
    fun normalisation() = listOf(
        "Salt & Signal's" to "salt and signals",
        "Salt.and.Signals" to "salt and signals",
        "Café Nöir" to "cafe noir",
        "  The   Quiet: Orbit! " to "the quiet orbit",
        "Weißenwald" to "weissenwald",
        "WEIẞENWALD" to "weissenwald",
        "Bølgehavn" to "bolgehavn",
        "Łódź" to "lodz",
        "Đorđe" to "dorde",
        "Þokuvík" to "thokuvik",
        "Brúðarvík" to "brudarvik",
        "Kırmızı" to "kirmizi",
    ).map { (input, expected) -> DynamicTest.dynamicTest(input) { assertEquals(expected, normalizeTitle(input)) } }

    /** Keys stay the same on devices whose locale writes other digits (Arabic, Persian). */
    @Test
    fun `keys use ascii digits whatever the device locale`() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale("ar"))
        try {
            assertEquals("harbor lights s05e03", ReleaseParser.parse("Harbor.Lights.S05E03.1080p.WEB-DL.H.264-GRP").groupKey)
            assertEquals("harbor lights s05", ReleaseParser.parse("Harbor.Lights.S05.1080p.WEB-DL.H.264-PACK").groupKey)
        } finally {
            Locale.setDefault(saved)
        }
    }

    /** Scene names spell umlauts ae/oe/ue (or drop the dots) and ligatures as two letters; metadata titles keep them. */
    @TestFactory
    fun spellingsShareAKey() = listOf(
        "Wattwürmer für Anfänger" to "Wattwuermer.fuer.Anfaenger",
        "Wattwürmer für Anfänger" to "Wattwurmer.fur.Anfanger",
        "Das Möwenmädchen" to "Das.Moewenmaedchen",
        "Die Göttinbucht" to "Die.Goettinbucht",
        "Böen" to "Boeen",
        "Æon Harbor" to "Aeon.Harbor",
        "ÆON HARBOR" to "AEON.HARBOR",
        "Cœur de phare" to "Coeur.de.phare",
        "Weißenwald" to "Weissenwald",
    ).map { (title, scene) ->
        DynamicTest.dynamicTest("$title = $scene") { assertEquals(normalizeTitle(scene), normalizeTitle(title)) }
    } + listOf(
        "Wattwuermer.fuer.Anfaenger.S01E01.German.720p.WEB.x264-GRP" to "Wattwürmer.für.Anfänger.S01E01.German.1080p.WEB.x264-GRP",
        "Aeon.Harbor.2005.1080p.BluRay.x264-GRP" to "Æon.Harbor.2005.720p.BluRay.x264-GRP",
    ).map { (scene, title) ->
        DynamicTest.dynamicTest("key of $title") { assertEquals(ReleaseParser.parse(scene).groupKey, ReleaseParser.parse(title).groupKey) }
    }
}
