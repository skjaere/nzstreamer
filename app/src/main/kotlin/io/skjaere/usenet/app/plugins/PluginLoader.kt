package io.skjaere.usenet.app.plugins

import androidx.annotation.VisibleForTesting
import dalvik.system.PathClassLoader
import io.skjaere.usenet.plugin.NzPlugin
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicInteger

/** What the registry asks of a plugin's file: its checks alone ([verify], before an install), or its checks and its code. */
interface PluginLoading {
    fun verify(apk: File, expected: Expectation): Verdict

    fun load(apk: File, expected: Expectation): LoadResult
}

sealed interface LoadResult {
    /** The entry's instance, made by the plugin's own class loader, and its file as the checks found it. */
    class Loaded(val plugin: NzPlugin, val facts: PluginFacts) : LoadResult

    /** The file didn't pass the checks: none of its classes loaded. */
    class Refused(val refusal: Refusal) : LoadResult

    /** The file passed, but its entry couldn't be made. */
    class Failed(val failure: LoadFailure) : LoadResult
}

/** Why a plugin's entry couldn't be made. The Plugins page says it in one way, [reason]; logs name the kind. */
enum class LoadFailure {
    /** The entry class isn't in the plugin's file. */
    Missing,

    /** The entry's name resolved to a class of the app's own, through the parent, not the plugin's (L583). */
    NotItsOwn,

    /** The entry class isn't an NzPlugin. */
    NotAPlugin,

    /** It has no public constructor without arguments, or that constructor threw. */
    Constructor,

    /** A class it needs is missing or isn't the one it was built against, or a static initializer threw (a LinkageError). */
    Linkage,

    /** Anything else that stopped it. */
    Other,
    ;

    /** The end of the Plugins page's "Couldn't start: <reason>." */
    val reason: String get() = "its code didn't load"
}

/**
 * Loads a plugin's code into NZStreamer's process (L579), only once [verifier] accepted its file: a plain PathClassLoader
 * over the file, with the app's own loader as its parent, so every shared class (the API, the UI kit, Compose, Kotlin) is
 * the app's, and ART can use what it compiles for it. Never createPackageContext, whose loader can't see Compose and which
 * gets the app killed as the plugin changes. The entry class is found without being initialized and must come from the
 * plugin's own loader: a name that resolves in the app through the parent never runs that class's static initializer.
 * Everything that can go wrong once the file passed, a LinkageError among it, is a reason, never a crash.
 */
class PluginLoader(
    private val verifier: PluginVerifier,
    private val appLoader: ClassLoader = PluginLoader::class.java.classLoader!!,
    private val classLoaderFor: (apk: File, parent: ClassLoader) -> ClassLoader = { apk, parent -> PathClassLoader(apk.path, null, parent) },
) : PluginLoading {
    private val made = AtomicInteger()

    /** How many class loaders were made over plugins' files: a device test's view that a refused file loaded nothing. */
    @VisibleForTesting
    internal val classLoadersMade: Int get() = made.get()

    override fun verify(apk: File, expected: Expectation): Verdict = verifier.verify(apk, expected)

    override fun load(apk: File, expected: Expectation): LoadResult {
        val facts = when (val verdict = verifier.verify(apk, expected)) {
            is Verdict.Refused -> return LoadResult.Refused(verdict.refusal)
            is Verdict.Accepted -> verdict.facts
        }
        // The verifier checked it: nothing outside the plugin's package is ever asked of a loader.
        if (!facts.entry.startsWith(PluginIds.packageOf(facts.id) + ".")) return LoadResult.Refused(Refusal.Entry)
        return try {
            made.incrementAndGet()
            val loader = classLoaderFor(apk, appLoader)
            val entry = Class.forName(facts.entry, false, loader)
            when {
                entry.classLoader !== loader -> LoadResult.Failed(LoadFailure.NotItsOwn)
                !NzPlugin::class.java.isAssignableFrom(entry) -> LoadResult.Failed(LoadFailure.NotAPlugin)
                else -> LoadResult.Loaded(entry.getConstructor().newInstance() as NzPlugin, facts)
            }
        } catch (e: ClassNotFoundException) {
            LoadResult.Failed(LoadFailure.Missing)
        } catch (e: InvocationTargetException) {
            LoadResult.Failed(if (e.cause is LinkageError) LoadFailure.Linkage else LoadFailure.Constructor)
        } catch (e: ReflectiveOperationException) {
            LoadResult.Failed(LoadFailure.Constructor)
        } catch (e: LinkageError) {
            LoadResult.Failed(LoadFailure.Linkage)
        } catch (e: Exception) {
            LoadResult.Failed(LoadFailure.Other)
        }
    }
}
