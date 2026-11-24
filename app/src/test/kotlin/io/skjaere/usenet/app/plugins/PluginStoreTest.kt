package io.skjaere.usenet.app.plugins

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L616, L617: a plugin's files in NZStreamer's own storage. Its code one folder per version, written read-only through a
 * .tmp; its data and cache folders emptied before an install hands them over; the registry written whole. At each start
 * nothing of an id the registry doesn't name outlives it: code, data, cache and secrets.
 */
class PluginStoreTest {
    @TempDir
    lateinit var dir: File

    private val files by lazy { File(dir, "files") }
    private val cache by lazy { File(dir, "cache") }
    private val secrets = FakeSecrets()
    private val store by lazy { PluginStore(files, cache, secrets) }

    private fun download(text: String) = File(dir, "download-${text.hashCode()}.apk").apply { writeText(text) }

    @Test
    fun `a version is written read-only, in a folder of its own, through a tmp beside it`() {
        val source = download("the skeleton, version 2")
        val apk = store.writeVersion("skeleton", 2, source, Sha256.of(source))
        assertEquals(File(files, "plugins/skeleton/code/2/plugin.apk"), apk)
        assertEquals("the skeleton, version 2", apk.readText())
        assertFalse(apk.canWrite(), "read-only")
        assertFalse(File(apk.parentFile, "plugin.apk.tmp").exists())
        assertEquals(apk, store.apk("skeleton", 2))
    }

    @Test
    fun `a copy that doesn't match its SHA-256 is refused and nothing is left`() {
        val source = download("the skeleton, version 2")
        assertFailsWith<IOException> { store.writeVersion("skeleton", 2, source, "0".repeat(64)) }
        assertFalse(File(files, "plugins/skeleton/code/2/plugin.apk").exists())
        assertFalse(File(files, "plugins/skeleton/code/2/plugin.apk.tmp").exists())
    }

    @Test
    fun `an id outside the rule never names a folder`() {
        listOf("../skeleton", "Skeleton", "true", "").forEach { id ->
            assertFailsWith<IllegalArgumentException>(id) { store.apk(id, 1) }
            assertFailsWith<IllegalArgumentException>(id) { store.dataDir(id) }
            assertFailsWith<IllegalArgumentException>(id) { store.cacheDir(id) }
        }
    }

    @Test
    fun `a tmp left by a death is removed at the next start`() = runBlocking<Unit> {
        val source = download("v1")
        val apk = store.writeVersion("skeleton", 1, source, Sha256.of(source))
        val tmp = File(apk.parentFile, "plugin.apk.tmp").apply { writeText("half a copy") }
        PluginStore(files, cache, secrets).sweep(mapOf("skeleton" to setOf(1L)))
        assertFalse(tmp.exists())
        assertTrue(apk.isFile)
    }

    @Test
    fun `an id the registry doesn't name loses its code, data, cache and secrets, and a version it doesn't keep goes`() = runBlocking<Unit> {
        listOf("skeleton" to 1L, "skeleton" to 2L, "kestrel" to 3L).forEach { (id, version) ->
            val source = download("$id $version")
            store.writeVersion(id, version, source, Sha256.of(source))
            store.dataDir(id).mkdirs()
            File(store.dataDir(id), "kept.json").writeText("{}")
            store.cacheDir(id).mkdirs()
            File(store.cacheDir(id), "list.json").writeText("{}")
        }
        secrets.values += mapOf(
            "plugin:skeleton:key" to "a", "plugin:kestrel:key" to "b", "plugin:kestrel:token" to "c",
            "plugin:kestrels:key" to "d", "tmdb:key" to "e", "indexer:1" to "f",
        )
        store.sweep(mapOf("skeleton" to setOf(2L)))

        assertFalse(File(files, "plugins/kestrel").exists(), "kestrel's code and data")
        assertFalse(File(cache, "plugins/kestrel").exists(), "kestrel's cache")
        assertEquals(setOf("plugin:skeleton:key", "tmdb:key", "indexer:1"), secrets.values.keys, "kestrel's secrets, and those of a plugin with no folder")
        assertFalse(File(files, "plugins/skeleton/code/1").exists(), "a version the registry doesn't keep")
        assertTrue(store.apk("skeleton", 2).isFile)
        assertTrue(File(store.dataDir("skeleton"), "kept.json").isFile)
        assertTrue(File(store.cacheDir("skeleton"), "list.json").isFile)
    }

    @Test
    fun `a registry that can't be read is empty, and everything of its ids then goes`() = runBlocking<Unit> {
        val source = download("v1")
        store.writeVersion("skeleton", 1, source, Sha256.of(source))
        store.writeRegistry(RegistryFile(listOf(record("skeleton", slot = 1))))
        assertEquals(listOf("skeleton"), store.readRegistry().plugins.map { it.id })
        secrets.values["plugin:skeleton:key"] = "a"

        File(files, "plugins/registry.json").writeText("""{"plugins":[{"id":"skel""")
        val registry = store.readRegistry()
        assertEquals(emptyList(), registry.plugins)
        store.sweep(registry.plugins.associate { it.id to setOf(it.versionCode) })
        assertFalse(File(files, "plugins/skeleton").exists())
        assertEquals(emptyMap(), secrets.values)
    }

    @Test
    fun `the registry is written whole, read with keys it doesn't know, and keeps only records it can use`() {
        store.writeRegistry(RegistryFile(listOf(record("skeleton", slot = 2), record("kestrel", slot = 1))))
        assertFalse(File(files, "plugins/registry.json.tmp").exists())
        assertEquals(listOf("skeleton" to 2, "kestrel" to 1), store.readRegistry().plugins.map { it.id to it.slot })

        File(files, "plugins/registry.json").writeText(
            """{"plugins":[
              {"id":"skeleton","name":"Skeleton","versionCode":2,"versionName":"2","sha256":"${"a".repeat(64)}","slot":1,"installedAt":1,"later":true},
              {"id":"../x","name":"X","versionCode":1,"versionName":"1","sha256":"${"a".repeat(64)}","slot":2,"installedAt":1},
              {"id":"skeleton","name":"Again","versionCode":1,"versionName":"1","sha256":"${"a".repeat(64)}","slot":3,"installedAt":1},
              {"id":"kestrel","name":"Kestrel","versionCode":1,"versionName":"1","sha256":"${"a".repeat(64)}","slot":9,"installedAt":1,"state":"Later"}
            ],"format":2}""",
        )
        val read = store.readRegistry().plugins
        assertEquals(listOf("skeleton", "kestrel"), read.map { it.id }, "an id outside the rule, and one given twice, left out")
        assertEquals("Skeleton", read.first().name)
        assertEquals(StoredState.On, read.last().state, "a state no build knows reads as on: the file is still checked before it loads")
        assertEquals(2, read.last().slot, "a slot outside the four, given the lowest free one")
    }

    @Test
    fun `an install's data and cache folders are found empty`() {
        store.dataDir("skeleton").mkdirs()
        File(store.dataDir("skeleton"), "left.json").writeText("an earlier install's")
        File(store.cacheDir("skeleton"), "deep").mkdirs()
        File(store.cacheDir("skeleton"), "deep/left.bin").writeText("an earlier install's")
        store.emptyFolders("skeleton")
        assertTrue(store.dataDir("skeleton").isDirectory)
        assertTrue(store.cacheDir("skeleton").isDirectory)
        assertEquals(emptyList(), store.dataDir("skeleton").list().orEmpty().toList())
        assertEquals(emptyList(), store.cacheDir("skeleton").list().orEmpty().toList())
    }

    @Test
    fun `a plugin's delete takes its code, data, cache and secrets, and nothing of another`() = runBlocking<Unit> {
        listOf("skeleton", "kestrel").forEach { id ->
            val source = download(id)
            store.writeVersion(id, 1, source, Sha256.of(source))
            store.emptyFolders(id)
        }
        secrets.values += mapOf("plugin:skeleton:key" to "a", "plugin:kestrel:key" to "b")
        store.delete("skeleton")
        assertFalse(File(files, "plugins/skeleton").exists())
        assertFalse(File(cache, "plugins/skeleton").exists())
        assertEquals(setOf("plugin:kestrel:key"), secrets.values.keys)
        assertTrue(store.apk("kestrel", 1).isFile)
        assertTrue(store.dataDir("kestrel").isDirectory)
    }

    private fun record(id: String, slot: Int) = PluginRecord(
        id = id, name = id, versionCode = 1, versionName = "1", sha256 = "a".repeat(64), slot = slot, installedAt = 1,
    )
}
