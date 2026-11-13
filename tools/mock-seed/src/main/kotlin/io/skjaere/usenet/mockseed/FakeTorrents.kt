package io.skjaere.usenet.mockseed

import java.security.MessageDigest

/**
 * How the Jackett-shaped feed hands out a torrent's hash, so each way the app reads one ([FakeTorznabServer]) has an
 * item. The Prowlarr-shaped feed gives the `infohash` attribute wherever there is a v1 hash.
 */
enum class HashForm {
    /** The `infohash` attribute, and a magnet holding the 40 hex digits: what most items carry. */
    Hex,

    /** A magnet whose `btih` is the 32-character base32 form, and no `infohash`. */
    Base32,

    /** A v1+v2 magnet (`btih` and `btmh`), and no `infohash`. */
    Hybrid,

    /** A v2-only magnet (`btmh` alone): no v1 hash anywhere. */
    V2Only,

    /** A `.torrent` link and nothing else: no hash without fetching the file. */
    None,
}

/**
 * One torrent the Torznab fake lists. Names and hashes are invented; sizes are what the feed claims, not files anywhere.
 */
data class FakeTorrent(
    val title: String,
    /** The v1 info hash, 40 lower-case hex digits; null for [HashForm.V2Only] and [HashForm.None]. */
    val hash: String?,
    val category: Int,
    val ids: FakeIds,
    val season: Int? = null,
    val episode: Int? = null,
    val sizeBytes: Long,
    /** Its files' paths, the torrent's folder first where it has one: the feed gives only their count. */
    val files: List<String>,
    val seeders: Int,
    val peers: Int,
    val grabs: Int,
    val ageDays: Int = 2,
    val form: HashForm = HashForm.Hex,
    /**
     * Jackett's guid is a tracker's download link with a passkey in its query, as Jackett fills a guid the site didn't
     * give (L168); else the magnet, or the details page for a torrent without one.
     */
    val passkeyGuid: Boolean = false,
) {
    /** A v2 hash for the magnets that carry one (`btmh`), made from the title: invented, and the same every run. */
    val v2Hash: String
        get() = MessageDigest.getInstance("SHA-256").digest(title.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * The torrents of the Torznab fake (L189): FakeCatalog's titles, named for the device cases, beside the 8089 indexer's
 * NZBs of the same show and film. Whether one is cached is the debrid fake's to say; each says which its case wants.
 */
object FakeTorrents {
    /** Indexers' own categories start at 100000; every torrent here is in this one as well as a standard one. */
    const val SITE_CATEGORY = 100001

    private const val TV_HD = FakeCatalog.TV_HD
    private const val MOVIES_HD = FakeCatalog.MOVIES_HD
    private const val MOVIES_UHD = 2045
    private val show = FakeCatalog.HARBOR_LIGHTS
    private val movie = FakeCatalog.COPPER_CROWN

    /** S01E02 under the name of Usenet's GOOD release: the same release from both sources. Meant cached. */
    val GOOD_S01E02 = single("Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD", "1bb5795cb9f72aa684193ddf405249b071b2ba5f", 2, 1_468_006_400, 210, 260, 1_800)

    /** A PROPER S01E01 that ranks just under Usenet's broken PROPER, for the play that moves from one to the other. Meant cached. */
    val PROPER_S01E01 = single("Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-TIDE", "b3d2a3297f45719d8d71c7cce68729bd32deb0be", 1, 1_510_000_000, 95, 120, 640)

    /** The season in three episodes, in a folder; a hybrid magnet on Jackett. Meant cached. */
    val PACK_S01 = FakeTorrent(
        title = "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-TIDE",
        hash = "b8e5ad63c94db5348740caabfbdca0083423ad21",
        category = TV_HD, ids = show, season = 1, sizeBytes = 4_404_019_200,
        files = (1..3).map { "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-TIDE/Harbor.Lights.S01E%02d.1080p.WEB-DL.AAC2.0.H.264-TIDE.mkv".format(it) },
        seeders = 60, peers = 72, grabs = 410, ageDays = 5, form = HashForm.Hybrid,
    )

    /** Meant uncached; a base32 magnet on Jackett. */
    val HDTV_S01E02 = single("Harbor.Lights.S01E02.720p.HDTV.x264-DRIFT", "945778eafec3446faf66dfa306f56aa069e1a30d", 2, 734_003_200, 14, 18, 90, form = HashForm.Base32)

    /** Meant uncached. */
    val HDTV_S01E01 = single("Harbor.Lights.S01E01.720p.HDTV.x264-DRIFT", "fd6bf9dba780bc442ab17a955144a47bd00367e9", 1, 734_003_200, 9, 11, 52)

    /** The film. Meant cached. */
    val CROWN_WEB = FakeTorrent(
        title = "Copper.Crown.2019.1080p.WEB-DL.DDP5.1.H.264-TIDE", hash = "81e639dbe53c58f9a63da37a37f29c66428ba3ab",
        category = MOVIES_HD, ids = movie, sizeBytes = 6_442_450_944, files = listOf("Copper.Crown.2019.1080p.WEB-DL.DDP5.1.H.264-TIDE.mkv"),
        seeders = 300, peers = 350, grabs = 5_200, ageDays = 40,
    )

    /** Meant uncached; on Jackett its guid is a download link with a passkey. */
    val CROWN_REMUX = FakeTorrent(
        title = "Copper.Crown.2019.2160p.BluRay.REMUX.HEVC.DTS-HD.MA.5.1-DRIFT", hash = "d91f0d7b58231dbc366e1e5209af5a7a3531edc6",
        category = MOVIES_UHD, ids = movie, sizeBytes = 32_212_254_720,
        files = listOf("Copper.Crown.2019.2160p.BluRay.REMUX.HEVC.DTS-HD.MA.5.1-DRIFT/Copper.Crown.2019.2160p.BluRay.REMUX.HEVC.DTS-HD.MA.5.1-DRIFT.mkv"),
        seeders = 40, peers = 55, grabs = 880, ageDays = 60, passkeyGuid = true,
    )

    /** Meant cached, but its video only in RAR volumes, which a debrid play can't stream (L180). */
    val CROWN_RAR = FakeTorrent(
        title = "Copper.Crown.2019.720p.HDTV.x264-PACKED", hash = "aef4f993d5b5bcef2d8bb28b7f4235b204192dfe",
        category = MOVIES_HD, ids = movie, sizeBytes = 2_147_483_648,
        files = listOf("rar", "r00", "r01", "nfo").map { "Copper.Crown.2019.720p.HDTV.x264-PACKED/copper.crown.2019.720p.hdtv.x264-packed.$it" },
        seeders = 12, peers = 15, grabs = 70, ageDays = 90,
    )

    /** Meant cached until its first link: then the service no longer has it, as a release taken out of a cache does. */
    val CROWN_FADES = FakeTorrent(
        title = "Copper.Crown.2019.720p.WEB-DL.AAC2.0.H.264-FADE", hash = "537787619aa52c6a81a85bb0020f895c458c30b5",
        category = MOVIES_HD, ids = movie, sizeBytes = 2_684_354_560, files = listOf("Copper.Crown.2019.720p.WEB-DL.AAC2.0.H.264-FADE.mkv"),
        seeders = 25, peers = 31, grabs = 160, ageDays = 30,
    )

    /** A v2-only torrent: no v1 hash, so a debrid service can't be asked about it. */
    val V2_ONLY = single("Harbor.Lights.S01E02.1080p.WEB.H265-VTWO", null, 2, 1_288_490_188, 5, 6, 12, form = HashForm.V2Only)

    /** A `.torrent` link alone: no hash without fetching it, which the app doesn't (L168). */
    val NO_HASH = single("Harbor.Lights.S01E02.1080p.WEB.H264-NOHASH", null, 2, 1_395_864_371, 30, 34, 150, form = HashForm.None)

    val all: List<FakeTorrent> =
        listOf(GOOD_S01E02, PROPER_S01E01, PACK_S01, HDTV_S01E02, HDTV_S01E01, CROWN_WEB, CROWN_REMUX, CROWN_RAR, CROWN_FADES, V2_ONLY, NO_HASH)

    /** `--extra-hash` and `--extra-title`: one more film, for the live device class (L212). */
    fun extra(hash: String, title: String) = FakeTorrent(
        title = title, hash = hash, category = MOVIES_HD, ids = FakeIds(), sizeBytes = 1_000_000_000, files = listOf("$title.mkv"),
        seeders = 50, peers = 60, grabs = 100,
    )

    /** [hex]'s 20 bytes in RFC 4648 base32, 32 characters, as some magnets carry a v1 hash. */
    fun base32(hex: String): String {
        require(hex.length == 40) { "a v1 hash is 40 hex digits" }
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        hex.chunked(2).forEach { byte ->
            buffer = (buffer shl 8) or byte.toInt(16)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buffer shr bits) and 31])
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        return out.toString()
    }

    private fun single(
        title: String, hash: String?, episode: Int, sizeBytes: Long, seeders: Int, peers: Int, grabs: Int, form: HashForm = HashForm.Hex,
    ) = FakeTorrent(
        title = title, hash = hash, category = TV_HD, ids = show, season = 1, episode = episode, sizeBytes = sizeBytes,
        files = listOf("$title.mkv"), seeders = seeders, peers = peers, grabs = grabs, form = form,
    )
}
