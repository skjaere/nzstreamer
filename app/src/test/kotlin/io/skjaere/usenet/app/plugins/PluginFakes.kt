@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.skjaere.usenet.app.plugins

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PluginApi
import io.skjaere.usenet.plugin.PluginHost
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.io.File

/** The app's one secret store as a map, by full name. */
internal class FakeSecrets : NamedSecrets {
    val values = mutableMapOf<String, String>()
    private val changes = MutableStateFlow(0)

    override suspend fun get(name: String): String? = values[name]

    override suspend fun put(name: String, value: String?) {
        if (value == null) values.remove(name) else values[name] = value
        changes.value++
    }

    override fun has(name: String): Flow<Boolean> = changes.map { name in values }

    override suspend fun removeAll(prefix: String, keep: (String) -> Boolean) {
        values.keys.removeAll { it.startsWith(prefix) && !keep(it) }
        changes.value++
    }
}

/**
 * A plugin in the test's own classes: what the host asked of it, and what it throws: an exception of its own, or the
 * throwable [tabError] or [closeError] gives (an Error, as Kotlin's TODO() throws). [onClose] runs as close() is called.
 */
internal class FakePlugin(
    private val label: String = "Fake",
    private val tabThrows: Boolean = false,
    private val closeThrows: Boolean = false,
    private val tabError: (() -> Throwable)? = null,
    private val closeError: (() -> Throwable)? = null,
    private val onClose: () -> Unit = {},
    private val onTab: () -> Unit = {},
) : NzPlugin {
    val calls = mutableListOf<String>()

    /** What the plugin's scope looked like as close() was called: whether it was cancelled first. */
    var scopeCancelledAtClose: Boolean? = null
    var host: PluginHost? = null

    override fun tab(): PluginTab {
        calls += "tab"
        onTab()
        if (tabThrows) throw IllegalStateException("the fake's tab, with a secret in its words: hunter2")
        tabError?.let { throw it() }
        return PluginTab(label, Icons.Filled.Star)
    }

    @Composable
    override fun Page(page: String, arg: String?, host: PluginHost, nav: PluginNav) {
    }

    @Composable
    override fun Settings(host: PluginHost, nav: PluginNav) {
    }

    override fun close() {
        calls += "close"
        scopeCancelledAtClose = host?.scope?.coroutineContext?.get(kotlinx.coroutines.Job)?.isCancelled
        onClose()
        if (closeThrows) throw IllegalStateException("the fake's close")
        closeError?.let { throw it() }
    }
}

/**
 * The crash guard as the registry tells it what happened: the versions it recorded an error for. It keeps no file; a
 * test turns the plugins off at the next start check with [pluginsOff], and Turn on again turns them on.
 */
internal class FakeGuard : PluginGuard {
    val errors = mutableListOf<Pair<String, Long>>()
    var pluginsOff = false

    override fun recordError(id: String, versionCode: Long) {
        synchronized(errors) { errors += id to versionCode }
    }

    override fun blame(t: Throwable) {}

    override fun loading(id: String, versionCode: Long) {}

    override fun loadDone(id: String, versionCode: Long, running: Boolean) {}

    override fun letGo(id: String, versionCode: Long) {}

    override fun startCheck(toLoad: List<Pair<String, Long>>) = StartCheck(pluginsOff, crashed = emptySet(), errored = emptySet())

    override fun recordsApplied() {}

    override fun lasted() {}

    override fun turnOnPlugins() {
        pluginsOff = false
    }

    override fun turnedOnAgain(id: String, versionCode: Long) {}

    override fun removed(id: String) {}
}

/** The signer the test plugins' files have, and the one the fakes' verifier trusts. */
internal val TEST_PIN = "ab".repeat(32)

/** The libraries this build stamps, as a plugin built with it names them. */
internal val HOST_LIBS = "kotlin-stdlib=2.3.21,kotlinx-coroutines=1.11.0,kotlinx-serialization-json=1.11.0," +
    "compose-bom=2026.06.01,lifecycle-viewmodel-compose=2.10.0,activity-compose=1.13.0,slf4j-api=2.0.20"

/** A test's plugin file: "<id> <versionCode>", which [TextReader] reads as a plugin's archive of that id and version. */
internal fun pluginFile(dir: File, id: String, versionCode: Long): File =
    File(dir, "download-$id-$versionCode.apk").apply { writeText("$id $versionCode") }

/** Reads a test's plugin file ([pluginFile]) as a well-formed archive of its id and version, signed by [TEST_PIN]. */
internal object TextReader : ArchiveReader {
    override fun read(apk: File): ArchiveFacts {
        val (id, version) = apk.readText().split(' ')
        val meta = mapOf<String, Any?>(
            PluginApi.META_ID to id,
            PluginApi.META_ENTRY to "app.nzstreamer.plugin.$id.Entry",
            PluginApi.META_API_MAJOR to 1,
            PluginApi.META_API_MINOR to 0,
            PluginApi.META_LIBS to HOST_LIBS,
        )
        return ArchiveFacts(
            "app.nzstreamer.plugin.$id", version.toLong(), version, ArchiveSigners(SigningFacts(false, listOf(TEST_PIN)), emptyList()),
            object : ArchiveMeta {
                override fun int(name: String): Int? = meta[name] as? Int
                override fun string(name: String): String? = meta[name] as? String
            },
            hasResources = false,
        )
    }
}

/**
 * The loader with the real verifier over [TextReader], and plugins made in the test's own classes in place of a class
 * loader's: [plugin] makes each instance, and a version in [failing] doesn't load, as its code wouldn't.
 */
internal class FakeLoading : PluginLoading {
    private val verifier = PluginVerifier(TextReader, PluginCompat(1, 0, HOST_LIBS), trusted = { setOf(TEST_PIN) })
    var plugin: (id: String, versionCode: Long) -> NzPlugin = { _, _ -> FakePlugin() }
    val failing = mutableSetOf<Pair<String, Long>>()

    /** Versions whose load throws, as a loader would let a throwable it doesn't catch through: an Error of the plugin's. */
    val throwing = mutableMapOf<Pair<String, Long>, () -> Throwable>()

    /** Runs as each version's code loads, once its file passed: where its class loader would be at work. */
    var whileLoading: (id: String, versionCode: Long) -> Unit = { _, _ -> }

    /** Each load asked, by id and version, and each instance made. */
    val loads = mutableListOf<Pair<String, Long>>()
    val made = mutableListOf<NzPlugin>()

    override fun verify(apk: File, expected: Expectation): Verdict = verifier.verify(apk, expected)

    override fun load(apk: File, expected: Expectation): LoadResult {
        loads += expected.id to expected.versionCode
        val facts = when (val verdict = verifier.verify(apk, expected)) {
            is Verdict.Refused -> return LoadResult.Refused(verdict.refusal)
            is Verdict.Accepted -> verdict.facts
        }
        whileLoading(expected.id, expected.versionCode)
        if (expected.id to expected.versionCode in failing) return LoadResult.Failed(LoadFailure.Linkage)
        throwing[expected.id to expected.versionCode]?.let { throw it() }
        return LoadResult.Loaded(plugin(expected.id, expected.versionCode).also { made += it }, facts)
    }
}

/**
 * A registry over a test's own folder, as a process of the app makes it: its files under files/ and cache/, its work on
 * the test's virtual time. [registry] again is the next process over the same files.
 */
internal class RegistryHarness(private val dir: File, private val test: TestScope, var guard: PluginGuard = FakeGuard()) {
    val files = File(dir, "files")
    val cache = File(dir, "cache")
    val secrets = FakeSecrets()
    val loading = FakeLoading()
    val store = PluginStore(files, cache, secrets)
    private val dispatcher = StandardTestDispatcher(test.testScheduler)

    /** A registry over the files, its work in [scope]: by default the test's own, run by advanceUntilIdle and waited for. */
    fun registry(scope: CoroutineScope = test): PluginRegistry = PluginRegistry(
        store, loading, PluginHosts(store, secrets, main = dispatcher), guard, scope, io = dispatcher,
        now = { test.testScheduler.currentTime },
    )

    /** Installs [id]'s version [versionCode] as the Plugins page does with a download that passed the list's checks. */
    suspend fun install(registry: PluginRegistry, id: String, versionCode: Long): InstallResult {
        val download = pluginFile(dir, id, versionCode)
        return registry.install(download, InstallRequest(id, id.replaceFirstChar { it.uppercase() }, versionCode, Sha256.of(download)))
    }
}

internal fun PluginRegistry.installed(id: String): InstalledPlugin = plugins.value.orEmpty().single { it.id == id }

internal fun PluginRegistry.running(id: String): PluginInstance? = (installed(id).status as? PluginStatus.Running)?.instance
