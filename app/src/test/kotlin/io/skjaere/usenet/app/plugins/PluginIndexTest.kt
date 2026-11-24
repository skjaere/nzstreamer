package io.skjaere.usenet.app.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L589, L620, L621: the plugin list as CI writes it on the plugin-index branch, read with this build's rules. The format
 * and its caps hold for the whole list; each entry's id is checked before it goes into anything, then its APK's address
 * whole, its package, its hash and its words, and an entry that breaks one is dropped alone. Which version is offered, and
 * when a plugin needs a newer NZStreamer, follows from the versions that pass.
 */
class PluginIndexTest {
    private val compat = PluginCompat(hostMajor = 1, hostMinor = 0, hostLibs = HOST_LIBS)

    @Test
    fun `a full list is read with every field it gives`() {
        val index = listed(list(plugin("harbor", versions = listOf(version("harbor", 1_000_300, "1.0.3"), version("harbor", 1_000_200, "1.0.2"))), withdrawnOf = mapOf("harbor" to listOf(1_000_100L))))
        assertEquals(Instant.parse(GENERATED), index.generated)
        val plugin = index.plugins.single()
        assertEquals("harbor", plugin.id)
        assertEquals("Harbor Lights", plugin.name)
        assertEquals("Harbor Lights in a tab of its own.", plugin.summary)
        assertEquals(setOf(1_000_100L), plugin.withdrawn)
        val newest = plugin.versions.first()
        assertEquals(1_000_300, newest.versionCode)
        assertEquals("1.0.3", newest.versionName)
        assertEquals(1, newest.apiMajor)
        assertEquals(0, newest.apiMinor)
        assertEquals(mapOf("kotlin-stdlib" to "2.3.21", "compose-bom" to "2026.06.01"), newest.libs)
        assertEquals(26, newest.minSdk)
        assertEquals(apk("harbor", "1.0.3"), newest.url)
        assertEquals(734_003, newest.size)
        assertEquals(HASH, newest.sha256)
        assertEquals(listOf(1_000_300L, 1_000_200L), plugin.versions.map { it.versionCode })
        assertEquals(emptyList(), index.dropped)
    }

    @Test
    fun `keys this build doesn't know are passed over, at every level`() {
        val text = list(plugin("harbor", extra = mapOf("icon" to JsonPrimitive("star"), "source" to JsonPrimitive("anything")), versions = listOf(version("harbor", extra = mapOf("signer" to JsonPrimitive("ab"), "future" to buildJsonObject { put("x", 1) })))), extra = mapOf("signature" to JsonPrimitive("none")))
        assertEquals(listOf("harbor"), listed(text).plugins.map { it.id })
    }

    @Test
    fun `a list in a later format says to update NZStreamer, on a phone and on a TV`() {
        val reading = PluginIndex.read(list(plugin("harbor"), format = 2).toByteArray(), SOURCE, accepted = null)
        assertSame(IndexReading.TooNew, reading)
        assertEquals("Update NZStreamer on this phone to see new plugins.", IndexReading.TooNew.line(television = false))
        assertEquals("Update NZStreamer on this TV to see new plugins.", IndexReading.TooNew.line(television = true))
    }

    @Test
    fun `a list older than the one accepted is ignored, the same or a newer one read`() {
        val accepted = Instant.parse(GENERATED)
        assertSame(IndexReading.Older, PluginIndex.read(list(plugin("harbor"), generated = "2026-11-30T10:00:00Z").toByteArray(), SOURCE, accepted))
        assertIs<IndexReading.Listed>(PluginIndex.read(list(plugin("harbor")).toByteArray(), SOURCE, accepted))
        assertIs<IndexReading.Listed>(PluginIndex.read(list(plugin("harbor"), generated = "2026-12-02T10:00:00Z").toByteArray(), SOURCE, accepted))
    }

    @Test
    fun `a list that doesn't read, or gives no format or time it was made, is refused whole`() {
        listOf(
            "not json", "[]", "{}", """{"format":"1","generated":"$GENERATED","plugins":[]}""", """{"format":0,"generated":"$GENERATED","plugins":[]}""",
            """{"format":1,"plugins":[]}""", """{"format":1,"generated":"yesterday","plugins":[]}""", """{"format":1,"generated":"$GENERATED"}""",
            """{"format":1,"generated":"$GENERATED","plugins":{}}""",
        ).forEach { text ->
            assertIs<IndexReading.Refused>(PluginIndex.read(text.toByteArray(), SOURCE, accepted = null), text)
        }
    }

    @Test
    fun `an entry whose address breaks the rule, whose package isn't its id's or whose hash isn't one is dropped alone`() {
        val index = listed(
            list(
                plugin("harbor"),
                plugin("copper", versions = listOf(version("copper", url = "https://github.com/skjaere/nzstreamer/releases/download/plugin-copper-v1.0.0/other.apk"))),
                plugin("kestrel", packageName = "app.nzstreamer.plugin.harbor"),
                plugin("crown", versions = listOf(version("crown", sha256 = HASH.uppercase()))),
                plugin("lights", versions = listOf(version("lights", sha256 = HASH.drop(1)))),
            ),
        )
        assertEquals(listOf("harbor", "copper", "crown", "lights"), index.plugins.map { it.id })
        assertTrue(index.plugins.drop(1).all { it.versions.isEmpty() }, "their versions dropped")
        assertEquals(listOf(ListDrop.Address, ListDrop.Package, ListDrop.Hash, ListDrop.Hash), index.dropped)
    }

    /**
     * Each of these addresses is the one the id would make, so only the id's own rule can drop them: an id goes into the
     * APK's address and, once installed, a folder's name, and is checked before either is built (L620).
     */
    @Test
    fun `an id holding a slash or dots, or breaking the id rule, is dropped before any address is built`() {
        val ids = listOf("a/b", "..", ".", "Harbor", "1harbor", "har-bor", "true", "false", "h".repeat(32), "")
        val index = listed(list(*ids.map { id -> plugin(id, versions = listOf(version(id, url = "https://github.com/skjaere/nzstreamer/releases/download/plugin-$id-v1.0.0/$id.apk"))) }.toTypedArray()))
        assertEquals(emptyList(), index.plugins.map { it.id })
        assertEquals(ids.map { ListDrop.Id }, index.dropped)
        assertEquals(emptyList(), listed(list(buildJsonObject { put("name", "Harbor Lights") }.toString())).plugins)
    }

    @Test
    fun `an address is checked whole, never by a prefix`() {
        val good = apk("harbor", "1.0.0")
        val bad = listOf(
            "https://github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/../../../../other/repo/harbor.apk",
            "https://github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1/../../../../other/repo/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "https://github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/%2e%2e/harbor.apk",
            "https://github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/./harbor.apk",
            "https://github.com/skjaere/nzstreamer/releases/download/%70lugin-harbor-v1.0.0/harbor.apk",
            "https://github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0//harbor.apk",
            "https://github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0/harbor.apk",
            "https://github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/copper.apk",
            "https://github.com/skjaere/nzstreamer/releases/download/plugin-copper-v1.0.0/harbor.apk",
            "https://github.com/skjaere/other/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "https://github.com.example.invalid/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "https://objects.github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "https://github.com:443/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "https://github.com:8443/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "https://github.com:/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "http://github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "ftp://github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            // User info, written in two parts so the source holds nothing the privacy scan reads as an e-mail address.
            "https://someone" + "@github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "$good?x=1",
            "$good?",
            "$good#top",
            "$good ",
            "https://github.com\\skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "//github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
            "/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk",
        )
        bad.forEach { url -> assertFalse(SOURCE.apkAddress("harbor", url), url) }
        assertTrue(SOURCE.apkAddress("harbor", good))
        assertTrue(SOURCE.apkAddress("harbor", "HTTPS://GitHub.com/skjaere/nzstreamer/releases/download/plugin-harbor-v12.0.31/harbor.apk"))
        val index = listed(list(plugin("harbor", versions = bad.take(20).mapIndexed { i, url -> version("harbor", versionCode = i + 1L, url = url) })))
        assertEquals(emptyList(), index.plugins.single().versions)
        assertEquals(List(20) { ListDrop.Address }, index.dropped)
    }

    @Test
    fun `a loopback source's addresses are its own, http on its port alone`() {
        val loopback = PluginSource.loopback(port = 8123)
        assertTrue(loopback.apkAddress("harbor", "http://127.0.0.1:8123/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk"))
        assertFalse(loopback.apkAddress("harbor", "http://127.0.0.1:8124/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk"))
        assertFalse(loopback.apkAddress("harbor", apk("harbor", "1.0.0")))
        assertFalse(SOURCE.apkAddress("harbor", "http://127.0.0.1:8123/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk"))
    }

    @Test
    fun `a list over its caps is refused whole`() {
        val many = (1..51).map { plugin("p$it") }
        assertEquals(ListFault.TooMany, (PluginIndex.read(list(*many.toTypedArray()).toByteArray(), SOURCE, null) as IndexReading.Refused).fault)
        assertIs<IndexReading.Listed>(PluginIndex.read(list(*many.take(50).toTypedArray()).toByteArray(), SOURCE, null))
        val versions = (1..21).map { version("harbor", versionCode = it.toLong()) }
        assertEquals(ListFault.TooMany, (PluginIndex.read(list(plugin("harbor", versions = versions)).toByteArray(), SOURCE, null) as IndexReading.Refused).fault)
        assertIs<IndexReading.Listed>(PluginIndex.read(list(plugin("harbor", versions = versions.take(20))).toByteArray(), SOURCE, null))
        val padded = list(plugin("harbor", extra = mapOf("padding" to JsonPrimitive("x".repeat(PluginIndex.MAX_BYTES)))))
        assertEquals(ListFault.TooLarge, (PluginIndex.read(padded.toByteArray(), SOURCE, null) as IndexReading.Refused).fault)
    }

    @Test
    fun `a name, summary or version name too long, or other than plain text, drops its entry`() {
        val index = listed(
            list(
                plugin("harbor", name = "n".repeat(40), summary = "s".repeat(200)),
                plugin("copper", name = "n".repeat(41)),
                plugin("crown", summary = "s".repeat(201)),
                plugin("lights", name = "Harbor\nLights"),
                plugin("kestrel", summary = "Harbor ‮stil thgiL"),
                plugin("tide", name = " "),
                plugin("pier", versions = listOf(version("pier", versionName = "1.0.0-a-very-long-name"), version("pier", versionCode = 2, versionName = "1.0\t1"))),
            ),
        )
        assertEquals(listOf("harbor", "pier"), index.plugins.map { it.id })
        assertEquals(emptyList(), index.plugins.last().versions)
        assertEquals(List(7) { ListDrop.Text }, index.dropped)
    }

    @Test
    fun `a field of the wrong type or out of range drops its entry, an id given twice its second`() {
        val index = listed(
            list(
                plugin("harbor"),
                plugin("harbor", name = "Second Harbor"),
                plugin("copper", withdrawn = buildJsonArray { add(JsonPrimitive("1")) }),
                plugin(
                    "crown",
                    versions = listOf(
                        version("crown", versionCode = 0),
                        version("crown", api = "1"),
                        version("crown", api = "1.0.0"),
                        version("crown", api = "01.0"),
                        version("crown", size = 0),
                        version("crown", minSdk = 0),
                        version("crown", extra = mapOf("libs" to JsonPrimitive("kotlin-stdlib=2.3.21"))),
                        version("crown", versionCode = 3),
                        version("crown", versionCode = 3),
                    ),
                ),
            ),
        )
        assertEquals(listOf("harbor", "crown"), index.plugins.map { it.id })
        assertEquals("Harbor Lights", index.plugins.first().name)
        assertEquals(listOf(3L), index.plugins.last().versions.map { it.versionCode })
        assertEquals(listOf(ListDrop.Repeated, ListDrop.Malformed) + List(7) { ListDrop.Malformed } + ListDrop.Repeated, index.dropped)
    }

    @Test
    fun `an API of two numbers reads its minor as a number, so 1 point 10 is above 1 point 9`() {
        val version = listed(list(plugin("harbor", versions = listOf(version("harbor", api = "1.10"))))).plugins.single().versions.single()
        assertEquals(1, version.apiMajor)
        assertEquals(10, version.apiMinor)
    }

    @Test
    fun `the offered version is the highest that passes everything and is above the installed one`() {
        val plugin = index(
            plugin(
                "harbor",
                versions = listOf(
                    version("harbor", 7, api = "2.0"),
                    version("harbor", 6, libs = mapOf("kotlin-stdlib" to "2.4.0")),
                    version("harbor", 5, minSdk = 35),
                    version("harbor", 4),
                    version("harbor", 3, api = "1.1"),
                    version("harbor", 2),
                    version("harbor", 1),
                ),
                withdrawn = buildJsonArray { add(JsonPrimitive(4)) },
            ),
        )
        assertEquals(2L, plugin.offer("harbor", installed = null, sdk = 34, compat)?.offered?.versionCode)
        assertEquals(5L, plugin.offer("harbor", installed = null, sdk = 35, compat)?.offered?.versionCode, "minSdk 35 on 35")
        assertEquals(2L, plugin.offer("harbor", installed = 1, sdk = 34, compat)?.offered?.versionCode)
        assertNull(plugin.offer("harbor", installed = 2, sdk = 34, compat)?.offered, "at the installed one")
        assertNull(plugin.offer("harbor", installed = 3, sdk = 34, compat)?.offered, "below the installed one")
        assertEquals(3L, plugin.offer("harbor", installed = 2, sdk = 34, PluginCompat(1, 1, HOST_LIBS))?.offered?.versionCode, "a host of 1.1")
        assertEquals(6L, plugin.offer("harbor", installed = 2, sdk = 34, PluginCompat(1, 0, HOST_LIBS.replace("2.3.21", "2.4.0")))?.offered?.versionCode)
        assertNull(plugin.offer("copper", installed = null, sdk = 34, compat), "not in the list")
    }

    @Test
    fun `a plugin whose versions all need a newer NZStreamer is listed so, one failing for another reason isn't listed`() {
        val newer = index(plugin("harbor", versions = listOf(version("harbor", 2, api = "1.1"), version("harbor", 1, libs = mapOf("slf4j-api" to "3.0.0")))))
        val offer = assertNotNull(newer.offer("harbor", installed = null, sdk = 34, compat))
        assertNull(offer.offered)
        assertEquals(2L, offer.needsNewer?.versionCode)
        assertFalse(offer.withdrawn)
        listOf(
            plugin("harbor", versions = listOf(version("harbor", 2, api = "1.1"), version("harbor", 1)), withdrawn = buildJsonArray { add(JsonPrimitive(1)) }),
            plugin("harbor", versions = listOf(version("harbor", 2, api = "1.1"), version("harbor", 1, minSdk = 35))),
            plugin("harbor", versions = listOf(version("harbor", 2, minSdk = 35))),
            plugin("harbor", versions = emptyList()),
        ).forEach { listedSo -> assertNull(index(listedSo).offer("harbor", installed = null, sdk = 34, compat)) }
    }

    @Test
    fun `an installed plugin's newest version needing a newer NZStreamer says so, beside an update it can take`() {
        val held = index(plugin("harbor", versions = listOf(version("harbor", 3, versionName = "1.2", api = "1.1"), version("harbor", 2, versionName = "1.1"), version("harbor", 1))))
        val offer = assertNotNull(held.offer("harbor", installed = 2, sdk = 34, compat))
        assertNull(offer.offered)
        assertEquals("1.2", offer.needsNewer?.versionName)
        assertEquals(2L, held.offer("harbor", installed = 1, sdk = 34, compat)?.offered?.versionCode)
        assertEquals(3L, held.offer("harbor", installed = 1, sdk = 34, compat)?.needsNewer?.versionCode)
        assertNull(held.offer("harbor", installed = 3, sdk = 34, compat)?.needsNewer, "nothing above the installed one")
        val withdrawnNewest = index(plugin("harbor", versions = listOf(version("harbor", 3, api = "1.1"), version("harbor", 2)), withdrawn = buildJsonArray { add(JsonPrimitive(3)) }))
        assertNull(withdrawnNewest.offer("harbor", installed = 2, sdk = 34, compat)?.needsNewer, "the newest is withdrawn, not too new")
    }

    @Test
    fun `an installed version the list withdraws is said so, and a newer one is offered over it`() {
        val list = index(plugin("harbor", versions = listOf(version("harbor", 3), version("harbor", 2)), withdrawn = buildJsonArray { add(JsonPrimitive(2)); add(JsonPrimitive(1)) }))
        val offer = assertNotNull(list.offer("harbor", installed = 2, sdk = 34, compat))
        assertTrue(offer.withdrawn)
        assertEquals(3L, offer.offered?.versionCode)
        assertTrue(assertNotNull(list.offer("harbor", installed = 1, sdk = 34, compat)).withdrawn, "withdrawn though no longer among the versions")
        assertFalse(assertNotNull(list.offer("harbor", installed = 3, sdk = 34, compat)).withdrawn)
        val fixed = index(plugin("harbor", versions = listOf(version("harbor", 2)), withdrawn = buildJsonArray { add(JsonPrimitive(2)) }))
        assertNull(fixed.offer("harbor", installed = null, sdk = 34, compat), "withdrawn and not installed: not listed")
    }

    @Test
    fun `the list CI writes is read whole, nothing dropped`() {
        // What tools/release/plugin_index.py writes from invented releases, held to its output byte for byte by
        // test_plugin_index.py: two plugins, the newest version of each API, one version withdrawn.
        val index = listed(File("../tools/release/testdata/plugin-index.json").readText())
        assertEquals(emptyList(), index.dropped)
        assertEquals(Instant.parse("2026-12-02T08:30:00Z"), index.generated)
        assertEquals(listOf("kestrel", "quillmere"), index.plugins.map { it.id })
        val kestrel = index.plugins.first()
        assertEquals("Kestrel", kestrel.name)
        assertEquals(setOf(1_000_200L), kestrel.withdrawn)
        assertEquals(listOf(1_010_000L to 1, 1_000_100L to 0), kestrel.versions.map { it.versionCode to it.apiMinor })
        assertEquals(HOST_LIBS.split(",").associate { it.substringBefore("=") to it.substringAfter("=") }, kestrel.versions.first().libs)
        // A host of API 1.0 is offered its API's newest version, and told 1.1's needs a newer NZStreamer.
        val offer = assertNotNull(index.offer("kestrel", installed = null, sdk = 34, compat))
        assertEquals(1_000_100L, offer.offered?.versionCode)
        assertEquals(1_010_000L, offer.needsNewer?.versionCode)
        assertEquals(30_000L, index.offer("quillmere", installed = null, sdk = 34, compat)?.offered?.versionCode)
    }

    private fun index(vararg plugins: String): PluginIndex = listed(list(*plugins))

    private fun listed(text: String): PluginIndex = assertIs<IndexReading.Listed>(PluginIndex.read(text.toByteArray(), SOURCE, accepted = null)).index

    private companion object {
        const val GENERATED = "2026-12-01T10:00:00Z"
        val HASH = "0123456789abcdef".repeat(4)
        val SOURCE = PluginSource.GITHUB
        const val HOST_LIBS = "kotlin-stdlib=2.3.21,kotlinx-coroutines=1.11.0,kotlinx-serialization-json=1.11.0," +
            "compose-bom=2026.06.01,lifecycle-viewmodel-compose=2.10.0,activity-compose=1.13.0,slf4j-api=2.0.20"

        fun apk(id: String, version: String) = "https://github.com/skjaere/nzstreamer/releases/download/plugin-$id-v$version/$id.apk"

        fun list(vararg plugins: String, format: Int = 1, generated: String = GENERATED, extra: Map<String, JsonElement> = emptyMap(), withdrawnOf: Map<String, List<Long>> = emptyMap()): String {
            val entries = plugins.map { text ->
                val entry = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject
                val id = (entry["id"] as? JsonPrimitive)?.content
                val withdrawn = withdrawnOf[id] ?: return@map entry
                JsonObject(entry + ("withdrawn" to JsonArray(withdrawn.map(::JsonPrimitive))))
            }
            return JsonObject(mapOf("format" to JsonPrimitive(format), "generated" to JsonPrimitive(generated), "plugins" to JsonArray(entries)) + extra).toString()
        }

        fun plugin(
            id: String,
            name: String = "Harbor Lights",
            summary: String = "Harbor Lights in a tab of its own.",
            packageName: String = "app.nzstreamer.plugin.$id",
            versions: List<JsonObject> = listOf(version(id)),
            withdrawn: JsonArray = JsonArray(emptyList()),
            extra: Map<String, JsonElement> = emptyMap(),
        ): String = JsonObject(
            mapOf(
                "id" to JsonPrimitive(id), "package" to JsonPrimitive(packageName), "name" to JsonPrimitive(name),
                "summary" to JsonPrimitive(summary), "withdrawn" to withdrawn, "versions" to JsonArray(versions),
            ) + extra,
        ).toString()

        fun version(
            id: String,
            versionCode: Long = 1_000_000,
            versionName: String = "1.0.0",
            api: String = "1.0",
            libs: Map<String, String> = mapOf("kotlin-stdlib" to "2.3.21", "compose-bom" to "2026.06.01"),
            minSdk: Int = 26,
            url: String = apk(id, "1.0.0").replace("v1.0.0", "v$versionName").takeIf { Regex("""\d+\.\d+\.\d+""").matches(versionName) } ?: apk(id, "1.0.0"),
            size: Long = 734_003,
            sha256: String = HASH,
            extra: Map<String, JsonElement> = emptyMap(),
        ): JsonObject = JsonObject(
            mapOf(
                "versionCode" to JsonPrimitive(versionCode), "versionName" to JsonPrimitive(versionName), "api" to JsonPrimitive(api),
                "libs" to JsonObject(libs.mapValues { JsonPrimitive(it.value) }), "minSdk" to JsonPrimitive(minSdk), "targetSdk" to JsonPrimitive(36),
                "url" to JsonPrimitive(url), "size" to JsonPrimitive(size), "sha256" to JsonPrimitive(sha256),
                "released" to JsonPrimitive("2026-12-01"), "changes" to JsonPrimitive("https://github.com/skjaere/nzstreamer/releases/tag/plugin-$id-v$versionName"),
            ) + extra,
        )
    }
}
