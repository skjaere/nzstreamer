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
import kotlin.system.exitProcess

/**
 * Usage:
 *   seed   --video <file> --out <nzb> [--mock http://localhost:8081] [--segment 750000] [--rar <volumeBytes>]
 *   verify --nzb <nzb> --video <file> [--host localhost] [--port 1119]
 *   probe  --host <host> --port <port> [--tls true]
 *   indexer [--video build/sample.mkv] [--eac3-video build/sample-eac3.mkv] [--mock http://localhost:8081] [--port 8089] [--apikey fake-indexer-key] [--grab-limit <n>] [--api-limit <n>]
 *   torznab [--port 8096] [--apikey fake-torznab-key] [--extra-hash <hex> --extra-title <title>] [--fail 429-900] [--delay-ms <n>]
 *   debrid  [--port 8097] [--torbox-key fake-torbox-key] [--premiumize-key fake-premiumize-key] [--media build]
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull() ?: usage()
    val options = args.drop(1).chunked(2).associate { (key, value) -> key.removePrefix("--") to value }
    when (command) {
        "seed" -> seed(options)
        "verify" -> verify(options)
        "probe" -> probe(options)
        "indexer" -> indexer(options)
        "torznab" -> serveTorznab(options)
        "debrid" -> serveDebrid(options)
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
        torznab [--port 8096] [--apikey fake-torznab-key] [--extra-hash <hex> --extra-title <title>] [--fail 429-900] [--delay-ms <n>]
        debrid  [--port 8097] [--torbox-key fake-torbox-key] [--premiumize-key fake-premiumize-key] [--media build]
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
            "(refuse-keys, limit, down, slow, hold-check, cdn, cdn-expire, cdn-gone, cdn-dip, fail, deleted-links, plant, reset, counts; take and " +
            "give-back, a device test's turn). Cached: GOOD_S01E02, PROPER_S01E01, PACK_S01, CROWN_WEB, CROWN_RAR (RAR volumes alone), " +
            "CROWN_FADES (gone after its first link). Ctrl+C to stop.",
    )
    return server
}
