package io.skjaere.usenet.link

import io.skjaere.usenet.link.LinkMessage.Ack
import io.skjaere.usenet.link.LinkMessage.Cancel
import io.skjaere.usenet.link.LinkMessage.Final
import io.skjaere.usenet.link.LinkMessage.FinalAck
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkMessage.PairCommit
import io.skjaere.usenet.link.LinkMessage.PairConfirm
import io.skjaere.usenet.link.LinkMessage.PairNonce
import io.skjaere.usenet.link.LinkMessage.PairResult
import io.skjaere.usenet.link.LinkMessage.PairReveal
import io.skjaere.usenet.link.LinkMessage.PairWait
import io.skjaere.usenet.link.LinkMessage.Pause
import io.skjaere.usenet.link.LinkMessage.Ping
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.link.LinkMessage.Pong
import io.skjaere.usenet.link.LinkMessage.Refused
import io.skjaere.usenet.link.LinkMessage.Rejected
import io.skjaere.usenet.link.LinkMessage.Resume
import io.skjaere.usenet.link.LinkMessage.SeekBy
import io.skjaere.usenet.link.LinkMessage.SeekTo
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.LinkMessage.Stop
import io.skjaere.usenet.link.LinkMessage.Unknown
import io.skjaere.usenet.link.LinkMessage.Unpair
import org.junit.jupiter.api.Test
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The link's messages, protocol v1 (L25, L26), as JSON with their type in `t`: each one both ways, the wire names that
 * never change within v1, what a reader does with what a newer peer sends, and what it says of a frame it can't read
 * (L13): a reason, never the frame. The titles and names are invented.
 */
class LinkMessageTest {
    private val episode = Play.Episode(
        showTmdbId = 4_101, showName = "The Glass Orchard", showYear = 2021, aliases = listOf("Glasshagen"),
        tvdb = 39_001, tmdb = 4_101, imdb = "tt7700112", tvmaze = 50_210, season = 2, episode = 4, episodeName = "Frost Line",
        airDate = "2023-03-14", runtimeMinutes = 52, stillPath = "/a1b2C3_d-4.jpg", aired = false, originalLanguage = "nb",
        seasonEpisodes = 8,
    )
    private val movie = Play.Movie(
        tmdbId = 77_310, title = "Harbor Lights", year = 2019, imdb = "tt0110022", runtimeMinutes = 118,
        releaseDate = "2019-11-02", released = false, originalLanguage = "en",
    )

    /** One of each message, every field set off its default. */
    private val samples: List<LinkMessage> = listOf(
        Hello(
            pv = 1, minPv = 1, role = Role.Tv, name = "Living room TV", app = "0.9.0", purpose = Purpose.Pair,
            ready = Hello.Ready(provider = true, indexer = false, tmdb = true, debrid = true, needs = Need.TorrentIndexer), session = Hello.Session("s-81", mine = true),
        ),
        Refused(Refusal.TooMany),
        PairWait,
        PairCommit("9f".repeat(32)),
        PairNonce("0a".repeat(32)),
        PairReveal("ff".repeat(32)),
        PairConfirm(yes = true),
        PairResult(ok = false, reason = PairEnd.TimedOut),
        Play(seq = 1, episode = episode, from = Play.From(positionMs = 1_930_000, watched = true, ageMs = 86_400_000)),
        Play(seq = 2, movie = movie),
        Cancel(seq = 3, session = "s-81"),
        Pause(seq = 4, session = "s-81"),
        Resume(seq = 5, session = "s-81"),
        SeekTo(seq = 6, session = "s-81", positionMs = 3_734_000),
        SeekBy(seq = 7, session = "s-81", deltaMs = -30_000),
        Stop(seq = 8, session = "s-81"),
        Unpair,
        FinalAck(listOf("f-1", "f-2")),
        Ping,
        Ack(seq = 1, session = "s-81"),
        Rejected(seq = 2, reason = Rejection.NotReadyIndexer),
        Status(
            session = "s-81", state = Playback.Paused, mine = true, continued = true, title = "The Glass Orchard",
            subtitle = "S02E04 · Frost Line", watchKey = "episode:4101:2:4", positionMs = 1_930_000, durationMs = 3_120_000,
            failure = "Couldn't get the NZB on the TV",
            sheet = Status.Sheet(
                kind = SheetKind.Running, title = "The Glass Orchard", subtitle = "S02E04 · Frost Line", line = "Searched 2 indexers by TVDB id · 6 found",
                notice = "Release incomplete · trying release 2 of 3",
                steps = listOf(
                    Status.Step(SheetStep.FetchNzb, StepState.Done, millis = 600, detail = "2.9 MB"),
                    Status.Step(SheetStep.CheckAvailability, StepState.Running, done = 112, total = 256),
                    Status.Step(SheetStep.Open, StepState.Failed, millis = 900, failure = "Couldn't get the NZB on the TV"),
                ),
                attempt = 2, of = 3, release = "The.Glass.Orchard.S02E04.2160p.WEB-DL.DV.HDR10.DDP5.1.H.265-GRP", chips = listOf("2160p", "WEB-DL"),
                playsHere = true, lookingFor = "Looking for S02E04 'Frost Line'", stages = listOf(Status.Line("Searched 2 indexers by TVDB id", "4 results, none for S02E04")),
                note = "Indexers can also number some shows differently from TMDB.", hidden = listOf(Status.Hidden("The.Glass.Orchard.S02E04.720p-GRP", listOf("⚠ Passworded"))),
                hiddenCount = 3, failures = listOf(Status.Line("The.Glass.Orchard.S02E04.1080p-GRP", "Incomplete on the TV's providers · 41 of 256 checked articles missing")),
                skippedBefore = listOf(Status.Line("The.Glass.Orchard.S02E04.720p-GRP", "Skipped: missing on your providers (checked today)")),
                notTried = 2, skipped = 1, noProvider = "No Usenet provider is set up yet.",
                rows = listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks), noRoute = 1,
            ),
        ),
        Final(
            id = "f-1", session = "s-81", watchKey = "episode:4101:2:4", kind = FinalKind.Episode, title = "The Glass Orchard",
            showTmdbId = 4_101, season = 2, episode = 4, movieTmdbId = 77_310, positionMs = 1_930_000, durationMs = 3_120_000,
            watched = true, ageMs = 5_000,
        ),
        Pong,
        Unknown(seq = 9),
    )

    private fun roundTrip(message: LinkMessage): LinkMessage = read(assertNotNull(LinkCodec.encode(message), "$message wasn't encoded"))

    private fun read(payload: ByteArray): LinkMessage = assertIs<Decoded.Read>(LinkCodec.decode(payload), String(payload)).value

    private fun read(json: String): LinkMessage = read(json.toByteArray())

    private fun bad(json: String): BadFrame = bad(json.toByteArray())

    private fun bad(payload: ByteArray): BadFrame = assertIs<Decoded.Bad>(LinkCodec.decode(payload)).reason

    @Test
    fun `every message round-trips`() {
        // Every type the protocol has is among the samples.
        val types = LinkMessage.serializer().descriptor.getElementDescriptor(1).let { d -> (0 until d.elementsCount).map(d::getElementName) }.toSet()
        val sampled = samples.map { String(LinkCodec.encode(it)!!).substringAfter("\"t\":\"").substringBefore('"') }.toSet()
        assertEquals(types, sampled)
        for (message in samples) assertEquals(message, roundTrip(message), "$message")
        // And with every default left as it is.
        val sheet = Status("s-82", Playback.Starting, sheet = Status.Sheet(SheetKind.Searching, "The Glass Orchard"))
        for (message in listOf(Hello(1, 1, Role.Phone, "Galaxy phone"), PairResult(ok = true), Play(10), Ack(11), Status("s-82", Playback.Starting), sheet, Unknown())) {
            assertEquals(message, roundTrip(message), "$message")
        }
    }

    @Test
    fun `the wire names are v1's and never change`() {
        fun json(message: LinkMessage) = String(LinkCodec.encode(message)!!)
        assertEquals("""{"t":"ping"}""", json(Ping))
        assertEquals("""{"t":"hello","pv":1,"minPv":1,"role":"tv","name":"Living room TV"}""", json(Hello(1, 1, Role.Tv, "Living room TV")))
        assertEquals("""{"t":"hello","pv":1,"minPv":1,"role":"phone","name":"Galaxy phone","purpose":"use"}""", json(Hello(1, 1, Role.Phone, "Galaxy phone", purpose = Purpose.Use)))
        assertEquals("""{"t":"seek-by","seq":7,"session":"s-81","deltaMs":-30000}""", json(SeekBy(7, "s-81", -30_000)))
        assertEquals("""{"t":"final-ack","ids":["f-1"]}""", json(FinalAck(listOf("f-1"))))
        assertEquals("""{"t":"pair-result","ok":false,"reason":"declined"}""", json(PairResult(false, PairEnd.Declined)))
        assertEquals("""{"t":"pair-wait"}""", json(PairWait))
        // The pairing's 32-byte values as 64 lowercase hex digits, as both ends write and read them.
        val bytes = ByteArray(32) { it.toByte() }
        val digits = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        assertEquals(digits, Pairing.hex(bytes))
        assertEquals(bytes.toList(), Pairing.unhex(digits)?.toList())
        assertNull(Pairing.unhex(digits.uppercase()), "uppercase isn't v1's")
        assertEquals("""{"t":"pair-commit","c":"$digits"}""", json(PairCommit(digits)))
        assertEquals("""{"t":"pair-nonce","n":"$digits"}""", json(PairNonce(digits)))
        assertEquals("""{"t":"pair-reveal","n":"$digits"}""", json(PairReveal(digits)))
        assertEquals("""{"t":"pair-confirm","yes":true}""", json(PairConfirm(yes = true)))
        assertEquals("""{"t":"pair-result","ok":true}""", json(PairResult(ok = true)))
        assertEquals("""{"t":"unpair"}""", json(Unpair))
        assertEquals("""{"t":"pong"}""", json(Pong))
        val words = mapOf(
            Refusal.NotPaired to "not-paired", Refusal.NotPairing to "not-pairing", Refusal.Busy to "busy", Refusal.TooMany to "too-many",
            Refusal.UpdateTv to "update-tv", Refusal.UpdatePhone to "update-phone",
        )
        for ((reason, word) in words) assertEquals("""{"t":"refused","reason":"$word"}""", json(Refused(reason)))
        val rejections = mapOf(
            Rejection.BusySetup to "busy-setup", Rejection.NotReadyProvider to "not-ready-provider", Rejection.NotReadyIndexer to "not-ready-indexer",
            Rejection.NotCurrent to "not-current", Rejection.TooFast to "too-fast", Rejection.Unsupported to "unsupported",
            // D2's (L223): a TV whose debrid setup isn't done.
            Rejection.NotReadyService to "not-ready-service", Rejection.NotReadyServiceOff to "not-ready-service-off",
            Rejection.NotReadyTorrentIndexer to "not-ready-torrent-indexer",
        )
        for ((reason, word) in rejections) assertEquals("""{"t":"rejected","seq":1,"reason":"$word"}""", json(Rejected(1, reason)))
        val states = listOf("starting", "buffering", "playing", "paused", "failed", "stopped", "replaced")
        assertEquals(states, Playback.entries.filter { it != Playback.Unknown }.map { json(Status("s", it)).substringAfter("\"state\":\"").substringBefore('"') })
        assertEquals(listOf("episode", "movie"), FinalKind.entries.filter { it != FinalKind.Unknown }.map { it.wire })
        assertEquals(listOf("tv", "phone"), Role.entries.filter { it != Role.Unknown }.map { it.wire })
        assertEquals(listOf("use", "pair"), Purpose.entries.filter { it != Purpose.Unknown }.map { it.wire })
        assertEquals(listOf("declined", "timed-out", "closed", "not-saved"), PairEnd.entries.filter { it != PairEnd.Unknown }.map { it.wire })
        // The mirrored sheet's (L37).
        assertEquals(listOf("searching", "running", "choosing", "no-release", "failed", "no-provider"), SheetKind.entries.filter { it != SheetKind.Unknown }.map { it.wire })
        assertEquals(listOf("prepare", "fetch-nzb", "check-availability", "open", "probe-tracks"), SheetStep.entries.filter { it != SheetStep.Unknown }.map { it.wire })
        assertEquals(listOf("running", "done", "failed"), StepState.entries.filter { it != StepState.Unknown }.map { it.wire })
        assertEquals(
            """{"t":"status","session":"s-81","state":"starting","sheet":{"kind":"searching","title":"The Glass Orchard","line":"Searching 2 indexers"}}""",
            json(Status("s-81", Playback.Starting, sheet = Status.Sheet(SheetKind.Searching, "The Glass Orchard", line = "Searching 2 indexers"))),
        )
        // D2's (L223): what a TV's debrid setup lacks, the Searching sheet's rows and the releases left with no route.
        assertEquals(listOf("service", "service-off", "torrent-indexer"), Need.entries.filter { it != Need.Unknown }.map { it.wire })
        assertEquals(
            """{"t":"hello","pv":1,"minPv":1,"role":"tv","name":"Living room TV","ready":{"provider":false,"indexer":false,"tmdb":true,"debrid":true}}""",
            json(Hello(1, 1, Role.Tv, "Living room TV", ready = Hello.Ready(provider = false, indexer = false, tmdb = true, debrid = true))),
        )
        assertEquals(
            """{"t":"hello","pv":1,"minPv":1,"role":"tv","name":"Living room TV","ready":{"provider":false,"indexer":false,"tmdb":false,"needs":"service-off"}}""",
            json(Hello(1, 1, Role.Tv, "Living room TV", ready = Hello.Ready(provider = false, indexer = false, tmdb = false, needs = Need.ServiceOff))),
        )
        assertEquals(
            """{"t":"status","session":"s-81","state":"starting","sheet":{"kind":"searching","title":"Harbor Lights","rows":["prepare","open","probe-tracks"]}}""",
            json(Status("s-81", Playback.Starting, sheet = Status.Sheet(SheetKind.Searching, "Harbor Lights", rows = listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks)))),
        )
        assertEquals(
            """{"t":"status","session":"s-81","state":"failed","sheet":{"kind":"failed","title":"Harbor Lights","notTried":1,"noRoute":2}}""",
            json(Status("s-81", Playback.Failed, sheet = Status.Sheet(SheetKind.Failed, "Harbor Lights", notTried = 1, noRoute = 2))),
        )
    }

    /**
     * L223: a TV ready by Usenet, as every TV was before D2, says so in exactly the bytes it did: the new keys have defaults,
     * which aren't written, so its hello is today's whatever the phone's version.
     */
    @Test
    fun `a Usenet-ready hello's bytes are today's`() {
        val hello = Hello(1, 1, Role.Tv, "Living room TV", app = "0.9.0", ready = Hello.Ready(provider = true, indexer = true, tmdb = true), session = Hello.Session("s-81"))
        val today = """{"t":"hello","pv":1,"minPv":1,"role":"tv","name":"Living room TV","app":"0.9.0","ready":{"provider":true,"indexer":true,"tmdb":true},"session":{"id":"s-81"}}"""
        assertEquals(today, String(LinkCodec.encode(hello)!!))
        assertEquals(hello, read(today))
        assertEquals(Hello.Ready(provider = true, indexer = true, tmdb = true, debrid = false, needs = null), assertIs<Hello>(read(today)).ready)
    }

    /** What a phone from before D2 reads of a hello's `ready`: today's three fields, the link's own codec settings. */
    @Serializable
    private data class TodayReady(val provider: Boolean, val indexer: Boolean, val tmdb: Boolean)

    /** A phone from before D2's refusals: today's names, any other read as unknown, as the link reads every enum. */
    @Serializable(with = TodayRejection.Wire::class)
    private enum class TodayRejection(val wire: String) {
        BusySetup("busy-setup"), NotReadyProvider("not-ready-provider"), NotReadyIndexer("not-ready-indexer"), NotCurrent("not-current"),
        TooFast("too-fast"), Unsupported("unsupported"), Unknown("unknown"),
        ;

        object Wire : WireEnum<TodayRejection>("TodayRejection", entries, Unknown, TodayRejection::wire)
    }

    /**
     * L223, L26: a phone from before D2 reads a D2 TV's hello as it read every newer one: `debrid` and `needs` are keys it
     * doesn't know, and dropped, so `provider` and `indexer` say what they always said; D2's refusals read as unknown
     * ("Couldn't start on Living room TV").
     */
    @Test
    fun `a hello's debrid and needs are dropped by a reader from before them`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val ready = Hello.Ready(provider = false, indexer = false, tmdb = true, debrid = true, needs = Need.Service)
        val sent = String(LinkCodec.encode(Hello(1, 1, Role.Tv, "Living room TV", ready = ready))!!)
        val readyJson = sent.substringAfter("\"ready\":").removeSuffix("}")
        assertEquals(TodayReady(provider = false, indexer = false, tmdb = true), json.decodeFromString(TodayReady.serializer(), readyJson))
        for (reason in listOf(Rejection.NotReadyService, Rejection.NotReadyServiceOff, Rejection.NotReadyTorrentIndexer)) {
            val wire = String(LinkCodec.encode(Rejected(1, reason))!!).substringAfter("\"reason\":").removeSuffix("}")
            assertEquals(TodayRejection.Unknown, json.decodeFromString(TodayRejection.serializer(), wire), wire)
        }
        // And today's three still read as they were.
        assertEquals(TodayRejection.NotReadyIndexer, json.decodeFromString(TodayRejection.serializer(), "\"not-ready-indexer\""))
    }

    /** L223: a sheet from a TV before D2 sends no rows and no count of releases left with no route: they read as none. */
    @Test
    fun `a sheet from before D2 reads no rows and no releases left with no route`() {
        val searching = assertIs<Status>(read("""{"t":"status","session":"s-81","state":"starting","sheet":{"kind":"searching","title":"Harbor Lights","line":"Searching 2 indexers"}}"""))
        assertEquals(emptyList(), assertNotNull(searching.sheet).rows)
        val failed = assertIs<Status>(read("""{"t":"status","session":"s-81","state":"failed","sheet":{"kind":"failed","title":"Harbor Lights","notTried":2}}"""))
        assertEquals(0, assertNotNull(failed.sheet).noRoute)
        assertEquals(2, failed.sheet!!.notTried)
        // A row this version doesn't know reads as Unknown, which the phone leaves out, as a step it doesn't know.
        val later = assertIs<Status>(read("""{"t":"status","session":"s","state":"starting","sheet":{"kind":"searching","title":"x","rows":["prepare","later"]}}"""))
        assertEquals(listOf(SheetStep.Prepare, SheetStep.Unknown), later.sheet!!.rows)
    }

    /**
     * L206: a TV's torrent start names its Preparing step, "prepare", and a phone reads it back as it was sent. A phone from
     * before reads a step it doesn't know as Unknown, which its sheet leaves out (the case below), so the rest still shows.
     */
    @Test
    fun `a TV's Preparing step goes as prepare and reads back`() {
        val json = """{"t":"status","session":"s-81","state":"starting","sheet":{"kind":"running","title":"Harbor Lights",""" +
            """"steps":[{"step":"prepare","state":"done","millis":900},{"step":"open","state":"running"}]}}"""
        val status = assertIs<Status>(read(json))
        assertEquals(listOf("prepare", "open"), assertNotNull(status.sheet).steps.map { it.step.wire })
        assertEquals(json, String(LinkCodec.encode(status)!!))
    }

    @Test
    fun `a type this version doesn't know reads as Unknown, with its seq when it has one`() {
        assertEquals(Unknown(seq = 12), read("""{"t":"skip-intro","seq":12,"session":"s-81"}"""))
        assertEquals(Unknown(), read("""{"t":"volume","level":0.4}"""))
        assertEquals(Unknown(), read("""{"level":3,"t":"later"}"""))
    }

    @Test
    fun `fields this version doesn't know are ignored`() {
        assertEquals(Pause(4, "s-81"), read("""{"t":"pause","seq":4,"session":"s-81","fade":{"ms":300,"curve":[1,2]},"note":null}"""))
        assertEquals(Ping, read("""{"t":"ping","sentAt":123}"""))
        // The type needn't come first.
        assertEquals(Ack(1, "s-81"), read("""{"seq":1,"session":"s-81","t":"ack"}"""))
    }

    @Test
    fun `a v1 reader reads a newer hello`() {
        val hello = read(
            """{"t":"hello","pv":3,"minPv":1,"role":"tv","name":"Living room TV","app":"2.0.0","caps":{"hdr":["dv","hdr10"],"audio":"truehd"},
               "ready":{"provider":true,"indexer":true,"tmdb":false,"discover":true},"session":{"id":"s-9","mine":false,"since":12},"theme":"amber"}""",
        )
        assertEquals(Hello(3, 1, Role.Tv, "Living room TV", app = "2.0.0", ready = Hello.Ready(true, true, false), session = Hello.Session("s-9")), hello)
    }

    @Test
    fun `a value this version doesn't know reads as Unknown, in every enum`() {
        assertEquals(Rejected(5, Rejection.Unknown), read("""{"t":"rejected","seq":5,"reason":"later"}"""))
        assertEquals(Status("s-81", Playback.Unknown), read("""{"t":"status","session":"s-81","state":"later"}"""))
        assertEquals(Refused(Refusal.Unknown), read("""{"t":"refused","reason":"later"}"""))
        assertEquals(PairResult(false, PairEnd.Unknown), read("""{"t":"pair-result","ok":false,"reason":"later"}"""))
        val final = assertIs<Final>(
            read("""{"t":"final","id":"f","session":"s","watchKey":"k","kind":"later","title":"x","positionMs":1,"durationMs":2,"watched":false,"ageMs":3}"""),
        )
        assertEquals(FinalKind.Unknown, final.kind)
        val hello = assertIs<Hello>(read("""{"t":"hello","pv":1,"minPv":1,"role":"later","name":"n","purpose":"later"}"""))
        assertEquals(Role.Unknown, hello.role)
        assertEquals(Purpose.Unknown, hello.purpose)
        // What a TV's debrid setup lacks, from a newer TV: Unknown, which a phone reads as Usenet's words (L223).
        val needs = assertIs<Hello>(read("""{"t":"hello","pv":1,"minPv":1,"role":"tv","name":"n","ready":{"provider":false,"indexer":false,"tmdb":false,"needs":"later"}}"""))
        assertEquals(Hello.Ready(provider = false, indexer = false, tmdb = false, needs = Need.Unknown), needs.ready)
        val sheet = assertIs<Status>(
            read("""{"t":"status","session":"s","state":"starting","sheet":{"kind":"later","title":"x","steps":[{"step":"later","state":"later"}],"art":1}}"""),
        ).sheet
        assertEquals(Status.Sheet(SheetKind.Unknown, "x", steps = listOf(Status.Step(SheetStep.Unknown, StepState.Unknown))), sheet)
    }

    @Test
    fun `a frame that can't be read gives a reason, and nothing told of it holds the frame`() {
        val name = "Harbor.Lights.2019.2160p.UHD.BluRay.REMUX-ORCHARD"
        val cases = mapOf(
            name.toByteArray() + byteArrayOf(-1, -2) to BadFrame.NotUtf8,
            // A bare word reads as a JSON value of its own, not an object.
            name.toByteArray() to BadFrame.NoType,
            "{$name}".toByteArray() to BadFrame.NotJson,
            """{"t":"status","title":"$name"""".toByteArray() to BadFrame.NotJson,
            """{"title":"$name"}""".toByteArray() to BadFrame.NoType,
            """{"t":3,"title":"$name"}""".toByteArray() to BadFrame.NoType,
            """{"t":{"n":"$name"}}""".toByteArray() to BadFrame.NoType,
            """["$name"]""".toByteArray() to BadFrame.NoType,
            "\"$name\"".toByteArray() to BadFrame.NoType,
            """{"t":"status","session":"s","state":"playing","title":"$name","positionMs":"$name"}""".toByteArray() to BadFrame.BadFields,
            """{"t":"status","title":"$name"}""".toByteArray() to BadFrame.BadFields,
            """{"t":"rejected","seq":1,"reason":["$name"]}""".toByteArray() to BadFrame.BadFields,
            """{"t":"ack","seq":1e12,"session":"$name"}""".toByteArray() to BadFrame.BadFields,
        )
        for ((payload, reason) in cases) {
            val decoded = LinkCodec.decode(payload)
            assertEquals(Decoded.Bad(reason), decoded, String(payload))
            assertFalse("Harbor" in decoded.toString(), decoded.toString())
        }
        // A message read whole names its type alone when printed: a slip into a log can't carry a title or a name.
        val status = read("""{"t":"status","session":"s","state":"playing","title":"$name","subtitle":"$name","watchKey":"$name"}""")
        assertEquals("Status", status.toString())
        assertEquals("Read", LinkCodec.decode(LinkCodec.encode(status)!!).toString())
        for (message in samples) {
            val printed = message.toString()
            assertTrue(listOf("Glass", "Harbor", "Living", "s-81", "tt").none { it in printed }, printed)
        }
        assertEquals("Episode", episode.toString())
        assertEquals("Movie", movie.toString())
        // The mirrored sheet's parts too: each holds titles or release names.
        val sheet = assertNotNull(samples.filterIsInstance<Status>().single().sheet)
        for (part in listOf(sheet, sheet.steps.first(), sheet.stages.first(), sheet.hidden.first(), sheet.failures.first())) {
            val printed = part.toString()
            assertTrue(listOf("Glass", "Orchard", "Searched", "Skipped", "Incomplete", "Passworded").none { it in printed }, printed)
        }
    }

    @Test
    fun `nothing throws, whatever the bytes`() {
        val random = Random(20261101)
        val valid = samples.map { LinkCodec.encode(it)!! }
        repeat(5_000) {
            val payload = if (it % 2 == 0) {
                random.nextBytes(random.nextInt(1, 200))
            } else {
                // A valid message with bytes changed, cut short or doubled.
                val base = valid[random.nextInt(valid.size)].copyOf()
                repeat(random.nextInt(1, 4)) { base[random.nextInt(base.size)] = random.nextInt(256).toByte() }
                when (random.nextInt(3)) {
                    0 -> base
                    1 -> base.copyOf(random.nextInt(1, base.size))
                    else -> base + base
                }
            }
            // A result either way; the test fails on a throw.
            LinkCodec.decode(payload)
        }
    }

    @Test
    fun `brackets nested deeper than any message are refused before they are parsed`() {
        // Deep enough to overflow kotlinx-serialization's reader, which recurses on arrays.
        assertEquals(BadFrame.TooDeep, bad("{\"t\":\"ping\",\"x\":" + "[".repeat(30_000) + "]".repeat(30_000) + "}"))
        assertEquals(BadFrame.TooDeep, bad("[".repeat(LinkFrames.MAX_PAYLOAD)))
        // The limit counts the message's own object, and brackets inside strings don't count.
        val within = "{\"t\":\"ping\",\"x\":" + "[".repeat(LinkCodec.MAX_DEPTH - 1) + "]".repeat(LinkCodec.MAX_DEPTH - 1) + "}"
        assertEquals(Ping, read(within))
        assertEquals(BadFrame.TooDeep, bad("{\"t\":\"ping\",\"x\":" + "[".repeat(LinkCodec.MAX_DEPTH) + "]".repeat(LinkCodec.MAX_DEPTH) + "}"))
        assertEquals(Ack(1, "[".repeat(100) + "\\\"{"), read("{\"t\":\"ack\",\"seq\":1,\"session\":\"" + "[".repeat(100) + "\\\\\\\"{\"}"))
    }

    @Test
    fun `a message bigger than a frame isn't encoded`() {
        assertNull(LinkCodec.encode(Status("s-81", Playback.Playing, title = "x".repeat(LinkFrames.MAX_PAYLOAD))))
        val fits = Status("s-81", Playback.Playing, title = "x".repeat(LinkFrames.MAX_PAYLOAD - 200))
        assertEquals(fits, roundTrip(fits))
    }

    @Test
    fun `the commands are the ones with a seq`() {
        val commands = samples.filterIsInstance<LinkMessage.Command>().map { it::class.java.simpleName }.toSet()
        assertEquals(setOf("Play", "Cancel", "Pause", "Resume", "SeekTo", "SeekBy", "Stop"), commands)
    }
}
