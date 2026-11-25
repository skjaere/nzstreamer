package io.skjaere.usenet.mockseed

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.skjaere.compressionutils.generation.ArchiveGenerator
import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.config.NntpConfig
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.ktor.network.selector.SelectorManager
import io.skjaere.nntp.NntpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.exitProcess

/**
 * Usage:
 *   seed   --video <file> --out <nzb> [--mock http://localhost:8081] [--segment 750000] [--rar <volumeBytes>]
 *   verify --nzb <nzb> --video <file> [--host localhost] [--port 1119]
 *   probe  --host <host> --port <port> [--tls true]
 *   indexer [--video build/sample.mkv] [--eac3-video build/sample-eac3.mkv] [--mock http://localhost:8081] [--port 8089] [--apikey fake-indexer-key] [--grab-limit <n>] [--api-limit <n>]
 *   indexer --catalog screens --video <clip> --port <8093 or above> [--mock http://localhost:8081] [--apikey fake-indexer-key] [--segment 750000] [--catalog-file app/src/androidTest/assets/screens/catalog.json]
 *   torznab [--port 8096] [--apikey fake-torznab-key] [--extra-hash <hex> --extra-title <title>] [--fail 429-900] [--delay-ms <n>]
 *   debrid  [--port 8097] [--torbox-key fake-torbox-key] [--premiumize-key fake-premiumize-key] [--media build]
 *   sports  [--port 8098] [--indexer-port 8094] [--anchor <yyyy-mm-dd>] [--mock http://localhost:8081] [--seed true] [--video build/sample.mkv]
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: usage()
    val options = args.drop(1).chunked(2).associate { (key, value) -> key.removePrefix("--") to value }
    when (command) {
        "seed" -> seed(options)
        "verify" -> verify(options)
        "probe" -> probe(options)
        "indexer" -> when (options["catalog"]) {
            null -> indexer(options)
            "screens" -> serveScreens(options)
            else -> usage()
        }
        "torznab" -> serveTorznab(options)
        "debrid" -> serveDebrid(options)
        "sports" -> serveSports(options)
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println(
        """
        seed   --video <file> --out <nzb> [--mock http://localhost:8081] [--segment 750000] [--rar <volumeBytes>]
        verify --nzb <nzb> --video <file> [--host localhost] [--port 1119]
        probe  --host <host> --port <port> [--tls true]
        indexer [--video build/sample.mkv] [--eac3-video build/sample-eac3.mkv] [--mock http://localhost:8081] [--port 8089] [--apikey fake-indexer-key] [--grab-limit <n>] [--api-limit <n>]
        indexer --catalog screens --video <clip> --port <8093 or above> [--mock http://localhost:8081] [--apikey fake-indexer-key] [--segment 750000] [--catalog-file app/src/androidTest/assets/screens/catalog.json]
        torznab [--port 8096] [--apikey fake-torznab-key] [--extra-hash <hex> --extra-title <title>] [--fail 429-900] [--delay-ms <n>]
        debrid  [--port 8097] [--torbox-key fake-torbox-key] [--premiumize-key fake-premiumize-key] [--media build]
        sports  [--port 8098] [--indexer-port 8094] [--anchor <yyyy-mm-dd>] [--mock http://localhost:8081] [--seed true] [--video build/sample.mkv]
        """.trimIndent()
    )
    exitProcess(2)
}

private fun seed(options: Map<String, String>) {
    val video = File(options["video"] ?: usage())
    val out = File(options["out"] ?: usage())
    val data = video.readBytes()
    val files = options["rar"]?.toLong()?.let { volumeSize ->
        ArchiveGenerator.generate(data, volumeSize, ContainerType.RAR5, video.name)
            .map { SeedFile(it.filename, it.data) }
    } ?: listOf(SeedFile(video.name, data))

    val seeder = MockSeeder(
        mockHttpUrl = options["mock"] ?: "http://localhost:8081",
        segmentSize = options["segment"]?.toInt() ?: 750_000,
    )
    val nzb = seeder.seed(files)
    out.parentFile?.mkdirs()
    out.writeText(nzb)
    val segments = Regex("<segment ").findAll(nzb).count()
    println("Seeded ${files.size} file(s), $segments segment(s): ${files.joinToString { "${it.name} (${it.data.size} B)" }}")
    println("Wrote ${out.path}")
}

private fun verify(options: Map<String, String>) = runBlocking {
    val nzbBytes = File(options["nzb"] ?: usage()).readBytes()
    val expected = File(options["video"] ?: usage()).readBytes()
    val config = NntpConfig(
        host = options["host"] ?: "localhost",
        port = options["port"]?.toInt() ?: 1119,
        username = "",
        password = "",
        useTls = false,
        maxConnections = 4,
    )
    NzbStreamer.fromConfig(config).use { streamer ->
        val prepared = streamer.prepare(nzbBytes)
        check(prepared is PrepareResult.Success) { "prepare failed: $prepared" }
        val metadata = prepared.metadata
        val files = streamer.resolveStreamableFiles(metadata)
        println("Prepared ${metadata::class.simpleName}: ${files.joinToString { "${it.path} (${it.totalSize} B)" }}")
        val file = files.maxByOrNull { it.totalSize } ?: error("no streamable files")
        check(file.totalSize == expected.size.toLong()) { "size ${file.totalSize} != ${expected.size}" }

        val whole = ByteArrayOutputStream()
        streamer.streamFile(metadata, file) { channel -> channel.copyTo(whole) }
        check(whole.toByteArray().contentEquals(expected)) { "full stream differs from source" }
        println("Full stream OK (${whole.size()} B)")

        // A range that starts mid-segment and spans a segment boundary, like a player seek.
        val start = expected.size / 3L + 12_345
        val end = minOf(start + 1_000_000, expected.size - 1L)
        val ranged = ByteArrayOutputStream()
        streamer.streamFile(metadata, file, start..end) { channel -> channel.copyTo(ranged) }
        check(ranged.toByteArray().contentEquals(expected.copyOfRange(start.toInt(), end.toInt() + 1))) {
            "range $start..$end differs from source"
        }
        println("Range $start..$end OK (${ranged.size()} B)")
    }
}

private suspend fun ByteReadChannel.copyTo(out: ByteArrayOutputStream) {
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = readAvailable(buffer, 0, buffer.size)
        if (n == -1) break
        out.write(buffer, 0, n)
    }
}

// Connects to a real or mock NNTP server and reads the welcome line, to tell TCP, TLS and
// greeting problems apart without credentials.
private fun probe(options: Map<String, String>) = runBlocking {
    val host = options["host"] ?: usage()
    val port = options["port"]?.toInt() ?: usage()
    val useTls = options["tls"]?.toBoolean() ?: false
    SelectorManager(Dispatchers.IO).use { selector ->
        val started = System.nanoTime()
        val result = runCatching {
            withTimeout(20_000) { NntpClient.connect(host, port, selector, useTls) }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        result.fold(
            onSuccess = { client -> println("connected to $host:$port tls=$useTls in ${elapsedMs}ms"); client.close() },
            onFailure = { println("failed after ${elapsedMs}ms: $it"); it.printStackTrace() },
        )
    }
}

// Seeds every release of FakeCatalog into the mock NNTP server and serves them through a
// Newznab API, so search, ranking and fallback can be exercised from the emulator
// (indexer URL http://10.0.2.2:8089, provider 10.0.2.2:1119 without TLS).
private fun indexer(options: Map<String, String>) {
    val clips = mapOf(
        Clip.Default to File(options["video"] ?: "build/sample.mkv"),
        Clip.Eac3 to File(options["eac3-video"] ?: "build/sample-eac3.mkv"),
    ).mapValues { (_, file) ->
        check(file.isFile) { "${file.path} missing; see README (ffmpeg -i build/sample.mkv -c:v copy -c:a eac3 build/sample-eac3.mkv)" }
        file.readBytes()
    }
    val seeder = MockSeeder(mockHttpUrl = options["mock"] ?: "http://localhost:8081")
    val releases = FakeCatalog.releases().map { release ->
        val clip = clips.getValue(release.clip)
        release to seeder.seed(release.fileNames.map { SeedFile(it, clip) }, tag = release.guid, missingParts = release.missingParts)
    }
    val apiKey = options["apikey"] ?: "fake-indexer-key"
    val server = FakeIndexerServer(
        releases, apiKey, port = options["port"]?.toInt() ?: 8089, grabLimit = options["grab-limit"]?.toInt(),
        apiLimit = options["api-limit"]?.toInt() ?: 1000,
    )
    releases.forEach { (release, _) ->
        val notes = listOfNotNull(
            "passworded".takeIf { release.passworded },
            "missing parts ${release.missingParts}".takeIf { release.missingParts.isNotEmpty() },
            "${release.fileNames.size} files".takeIf { release.fileNames.size > 1 },
            "E-AC-3 audio".takeIf { release.clip == Clip.Eac3 },
        )
        println("  ${release.guid.padEnd(18)} ${release.title}${if (notes.isEmpty()) "" else "  (${notes.joinToString()})"}")
    }
    println("Fake indexer on http://localhost:${server.port}/api (emulator: http://10.0.2.2:${server.port}), API key $apiKey")
    println("Show ids: ${FakeCatalog.HARBOR_LIGHTS}; movie ids: ${FakeCatalog.COPPER_CROWN}. Ctrl+C to stop.")
    Thread.currentThread().join()
}

private fun serveScreens(options: Map<String, String>) {
    val server = try {
        screensIndexer(options)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        usage()
    }
    server.use { Thread.currentThread().join() }
}

/**
 * The README screenshots' indexer ([ScreensCatalog]): the clip seeded under each of its releases, whole for the ones
 * the screenshots play and with every article missing for the others, and served as a Newznab API (emulator:
 * http://10.0.2.2:<port>). tools/screens/make-clip.sh makes the clip. Its port is given, 8093 or above (0 for any free
 * one), never the device tests' fake indexer's 8089 or the ports beside it, and taken before anything is seeded, so a
 * port in use fails at once. Throws IllegalArgumentException for options it can't take and for a taken port.
 */
internal fun screensIndexer(options: Map<String, String>, log: (String) -> Unit = ::println): FakeIndexerServer {
    val clip = File(options["video"] ?: throw IllegalArgumentException("--catalog screens takes --video <clip>; tools/screens/make-clip.sh makes one"))
    require(clip.isFile) { "${clip.path} missing; tools/screens/make-clip.sh makes it" }
    val catalog = File(options["catalog-file"] ?: ScreensCatalog.FILE)
    require(catalog.isFile) { "${catalog.path} missing: run from the repository's top folder, or give --catalog-file" }
    val segment = options["segment"]?.let { requireNotNull(it.toIntOrNull()?.takeIf { size -> size > 0 }) { "--segment takes a size in bytes" } } ?: 750_000
    val port = requireNotNull(options["port"]?.toIntOrNull()?.takeIf { it == 0 || it in 8093..65535 }) {
        "--catalog screens takes --port <port>, 8093 or above: 8089 is the device tests' fake indexer, and the ports beside it are left to it"
    }
    val apiKey = options["apikey"] ?: "fake-indexer-key"
    val server = try {
        FakeIndexerServer(emptyList(), apiKey, port)
    } catch (e: BindException) {
        throw IllegalArgumentException("port $port is taken", e)
    }
    val releases = try {
        val data = clip.readBytes()
        val parts = (data.size + segment - 1) / segment
        val seeder = MockSeeder(mockHttpUrl = options["mock"] ?: "http://localhost:8081", segmentSize = segment)
        ScreensCatalog.releases(catalog).map { release ->
            release to seeder.seed(release.fileNames.map { SeedFile(it, data) }, tag = release.guid, missingParts = ScreensCatalog.missingParts(release, parts))
        }
    } catch (t: Throwable) {
        server.close()
        throw t
    }
    server.serve(releases)
    releases.forEach { (release, _) ->
        val seeded = if (release.guid in ScreensCatalog.PLAYABLE) "seeded whole" else "listed, every article missing"
        log("  ${release.guid.padEnd(30)} ${release.title}  ($seeded)")
    }
    log("Fake indexer (screens) on http://localhost:${server.port}/api (emulator: http://10.0.2.2:${server.port}), API key $apiKey. Ctrl+C to stop.")
    return server
}

private fun serveTorznab(options: Map<String, String>) {
    val server = try {
        torznab(options)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        usage()
    }
    server.use { Thread.currentThread().join() }
}

/**
 * Serves torrents through Torznab feeds shaped as Prowlarr's and Jackett's ([FakeTorznabServer]), on 127.0.0.1 alone
 * (emulator: http://10.0.2.2:8096). It seeds nothing, so it runs with the mock NNTP server down and never posts to it.
 * Throws IllegalArgumentException for options it can't take.
 */
internal fun torznab(options: Map<String, String>, log: (String) -> Unit = ::println): FakeTorznabServer {
    val extraHash = options["extra-hash"]
    val extraTitle = options["extra-title"]
    require((extraHash == null) == (extraTitle == null)) { "--extra-hash and --extra-title go together" }
    val extra = extraHash?.let { hash ->
        require(Regex("[0-9a-fA-F]{40}").matches(hash)) { "--extra-hash takes a v1 info hash, 40 hex digits" }
        FakeTorrents.extra(hash.lowercase(), requireNotNull(extraTitle))
    }
    val fail = options["fail"]?.let { value ->
        require(value == "429-900") { "--fail takes 429-900" }
        FakeTorznabServer.Fail.Limit429Code900
    }
    val apiKey = options["apikey"] ?: "fake-torznab-key"
    val server = FakeTorznabServer(
        apiKey = apiKey,
        port = options["port"]?.toInt() ?: 8096,
        extra = extra,
        fail = fail,
        delayMs = options["delay-ms"]?.toLong() ?: 0,
        log = log,
    )
    FakeTorrents.all.forEach { t -> log("  ${(t.hash ?: "no v1 hash (${t.form})").padEnd(40)}  ${t.title}") }
    // The extra one is a real film's torrent: neither its hash nor its name is printed.
    if (extra != null) log("  plus one extra film (--extra-title)")
    log(
        "Fake Torznab on http://127.0.0.1:${server.port} (emulator: http://10.0.2.2:${server.port}), API key $apiKey: " +
            "Prowlarr-shaped at ${FakeTorznabServer.Feed.Prowlarr.path}, Jackett-shaped at ${FakeTorznabServer.Feed.Jackett.path}" +
            listOfNotNull(fail?.let { " · every search answers 429 (code 900)" }, options["delay-ms"]?.let { " · searches wait $it ms" }).joinToString(""),
    )
    log("Show ids: ${FakeCatalog.HARBOR_LIGHTS}; movie ids: ${FakeCatalog.COPPER_CROWN}. Ctrl+C to stop.")
    return server
}

private fun serveDebrid(options: Map<String, String>) {
    val server = try {
        debrid(options)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        usage()
    }
    server.use { Thread.currentThread().join() }
}

/**
 * Serves TorBox- and Premiumize-shaped APIs over the Torznab fake's torrents ([FakeDebridServer]), and the Range CDN their
 * links point at, on 127.0.0.1 alone (emulator: http://10.0.2.2:8097), every video file the media folder's sample.mkv. It
 * seeds nothing and never prints a key. Throws IllegalArgumentException for options it can't take.
 */
internal fun debrid(options: Map<String, String>, log: (String) -> Unit = ::println): FakeDebridServer {
    val server = FakeDebridServer(
        torboxKey = options["torbox-key"] ?: "fake-torbox-key",
        premiumizeKey = options["premiumize-key"] ?: "fake-premiumize-key",
        media = File(options["media"] ?: "build"),
        port = options["port"]?.toInt() ?: 8097,
        log = log,
    )
    log(
        "Fake debrid services on http://127.0.0.1:${server.port} (emulator: http://10.0.2.2:${server.port}): TorBox-shaped at " +
            "/torbox/v1/api, Premiumize-shaped at /premiumize/api, their links' Range CDN at /cdn, faults and counts at /control " +
            "(refuse-keys, limit, down, slow, hold-check, cdn, cdn-expire, cdn-gone, cdn-dip, fail, deleted-links, plant, fade, media, reset, " +
            "counts; take and give-back, a device test's turn). Cached: GOOD_S01E02, PROPER_S01E01, PACK_S01, CROWN_WEB, CROWN_RAR (RAR volumes alone), " +
            "CROWN_FADES (gone after its first link). Ctrl+C to stop.",
    )
    return server
}

private fun serveSports(options: Map<String, String>) {
    val fakes = try {
        sports(options)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        usage()
    }
    fakes.use { Thread.currentThread().join() }
}

/** The sports fakes of one `sports` command (L655): the fake Sportarr and the sports indexer, on one anchor. */
class SportsFakes(val sportarr: FakeSportarrServer, val indexer: FakeIndexerServer) : AutoCloseable {
    /** The date every start, release name and posted time is counted from. */
    val anchor: LocalDate get() = sportarr.anchor

    override fun close() {
        sportarr.close()
        indexer.close()
    }

    companion object {
        /** The sports indexer's key, the device tests' fake indexer's. */
        const val INDEXER_KEY = "fake-indexer-key"

        /**
         * Ports in use: the mock's (1119, 8081), the device tests' indexer (8089), mock-up.sh's and the screens indexer's (8090,
         * 8093), the debrid fakes' (8096, 8097), and others' (8091, 8092, 29101).
         */
        internal val TAKEN = setOf(1119, 8081, 8089, 8090, 8091, 8092, 8093, 8096, 8097, 29101)
    }
}

/**
 * The sports fakes (L655), one process with one clock: the fake Sportarr ([FakeSportarrServer]) over the catalogue's
 * library ([SportsCatalog]) and a second Newznab indexer, the sports indexer, over the catalogue's releases, never the
 * device tests' 8089 one. Both listen on 127.0.0.1 alone (emulator: 10.0.2.2), on --port and --indexer-port (0 for any
 * free one, never a port another fake or the mock uses), both taken before anything is seeded, so a port in use fails at
 * once. --anchor is the date every start, release name and posted time counts from (the UTC date it starts, unless
 * given). The releases marked whole are seeded into the mock with the clip (--video, the sample clip) under their names,
 * and the others with every article missing, as the screens catalogue's are, so the indexer lists them and a play of one
 * fails as a broken release would; with --seed false they are listed alone, nothing is posted and no clip is needed. The
 * Sportarr key isn't printed. Throws IllegalArgumentException for options it can't take and for a taken port.
 */
internal fun sports(options: Map<String, String>, log: (String) -> Unit = ::println): SportsFakes {
    fun port(name: String, default: Int): Int = options[name]?.let { given ->
        requireNotNull(given.toIntOrNull()?.takeIf { it in 0..65_535 && it !in SportsFakes.TAKEN }) {
            "--$name takes a port of its own, 0 for any free one: 1119 and 8081 are the mock's, 8089 the device tests' indexer, " +
                "8096 and 8097 the debrid fakes', and 8090 to 8093 and 29101 aren't free either"
        }
    } ?: default
    val port = port("port", 8098)
    val indexerPort = port("indexer-port", 8094)
    require(port == 0 || port != indexerPort) { "the fake Sportarr and the sports indexer take two ports" }
    val anchor = options["anchor"]?.let { given ->
        requireNotNull(runCatching { LocalDate.parse(given) }.getOrNull()) { "--anchor takes a date, yyyy-mm-dd" }
    } ?: LocalDate.now(ZoneOffset.UTC)
    val seed = when (options["seed"] ?: "true") {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("--seed takes true or false")
    }
    val clip = if (seed) File(options["video"] ?: "build/sample.mkv").also { require(it.isFile) { "${it.path} missing; see README" } } else null
    val catalog = SportsCatalog.load()
    val releases = catalog.releases(anchor)
    val sportarr = try {
        FakeSportarrServer(catalog, anchor, port, log = log)
    } catch (e: BindException) {
        throw IllegalArgumentException("port $port is taken", e)
    }
    val indexer = try {
        FakeIndexerServer(emptyList(), SportsFakes.INDEXER_KEY, indexerPort, bind = InetAddress.getLoopbackAddress())
    } catch (e: BindException) {
        sportarr.close()
        throw IllegalArgumentException("port $indexerPort is taken", e)
    }
    val listed = try {
        if (clip == null) {
            releases.map { it to unseededNzb(it.fake()) }
        } else {
            val data = clip.readBytes()
            val parts = (data.size + SEGMENT_BYTES - 1) / SEGMENT_BYTES
            val seeder = MockSeeder(mockHttpUrl = options["mock"] ?: "http://localhost:8081", segmentSize = SEGMENT_BYTES)
            releases.map { release ->
                val fake = release.fake()
                release to seeder.seed(fake.fileNames.map { SeedFile(it, data) }, tag = fake.guid, missingParts = if (release.whole) emptySet() else (1..parts).toSet())
            }
        }
    } catch (t: Throwable) {
        sportarr.close()
        indexer.close()
        throw t
    }
    indexer.serve(listed.map { (release, nzb) -> release.fake() to nzb })
    releases.forEach { release ->
        val seeded = when {
            clip == null -> "listed, nothing seeded"
            release.whole -> "seeded whole"
            else -> "listed, every article missing"
        }
        log("  ${release.fake().guid.padEnd(32)} ${release.name}  ($seeded)")
    }
    log(
        "Fake Sportarr on http://127.0.0.1:${sportarr.port} (emulator: http://10.0.2.2:${sportarr.port}), Sportarr ${FakeSportarrServer.VERSION} " +
            "with the device tests' key; anchor $anchor. Switches, the clock, counts and the lease at /control (refuse-key, version, html, " +
            "url-base, slow, fail, remove-league, order, paging, empty, busy, clock, counts, reset; take and give-back).",
    )
    log(
        "Sports indexer on http://127.0.0.1:${indexer.port}/api (emulator: http://10.0.2.2:${indexer.port}), API key ${SportsFakes.INDEXER_KEY}: " +
            "${releases.size} releases${if (clip == null) ", none seeded" else ", ${releases.count { it.whole }} of them whole"}. Ctrl+C to stop.",
    )
    return SportsFakes(sportarr, indexer)
}

/** The sports releases' segments: the size the indexer command seeds. */
private const val SEGMENT_BYTES = 750_000

/** An NZB for a release listed and never seeded (--seed false): one segment, which no server has. */
private fun unseededNzb(release: FakeRelease): String = buildString {
    appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
    appendLine("""<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb">""")
    release.fileNames.forEachIndexed { index, name ->
        appendLine("""  <file poster="seed@mock" date="0" subject="&quot;${xmlText(name)}&quot; yEnc (1/1)">""")
        appendLine("""    <groups><group>alt.binaries.test</group></groups>""")
        appendLine("""    <segments><segment bytes="750000" number="1">unseeded-${release.guid}-$index@mock</segment></segments>""")
        appendLine("  </file>")
    }
    appendLine("</nzb>")
}
