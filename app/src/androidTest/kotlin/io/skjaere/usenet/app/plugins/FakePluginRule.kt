package io.skjaere.usenet.app.plugins

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * In-process plugins of the test's own ([FakePlugin]) in slots, through a registry's test seam (PluginRegistry.installForTest,
 * L622, a debug build's alone): before the test, [fakes] in the app's registry, each running, in the lowest free slots, in
 * order; a test marked [FakeCount] gets that many of them (none, for one that starts with the slots free). Outside the
 * compose rule, so they are in before the test's activity starts and taken out once it has closed; inside [plugins], which
 * finds none before and clears everything after. After the test, whatever its outcome, every fake this rule put in that
 * its registry still lists is taken out as Remove takes a plugin out, so no other class sees it.
 */
class FakePluginRule(private val plugins: PluginStateRule, private vararg val fakes: FakePlugin) : TestRule {
    /** Each fake put in, with the registry it is in. */
    private val put = mutableListOf<Pair<PluginRegistry, FakePlugin>>()

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val count = description.getAnnotation(FakeCount::class.java)?.count ?: fakes.size
            try {
                fakes.take(count).forEach { install(it) }
                base.evaluate()
            } catch (e: Throwable) {
                try {
                    takeOut()
                } catch (after: Throwable) {
                    e.addSuppressed(after)
                }
                throw e
            }
            takeOut()
        }
    }

    /** Puts [fake] in [registry]'s lowest free slot (the app's unless given), and waits for it to run: its instance. */
    fun install(fake: FakePlugin, registry: PluginRegistry = plugins.app): PluginInstance = runBlocking {
        registry.installForTest(fake, fake.id)
        put += registry to fake
        val listed = registry.await(fake.id, "running") { it.status is PluginStatus.Running }
        (listed.status as PluginStatus.Running).instance
    }

    /** [fake]'s instance running in [registry] now (a new one after Turn on again); the test fails where none runs. */
    fun instance(fake: FakePlugin, registry: PluginRegistry = plugins.app): PluginInstance =
        (registry.plugins.value?.firstOrNull { it.id == fake.id }?.status as? PluginStatus.Running)?.instance
            ?: throw AssertionError("${fake.id} isn't running: the registry lists ${describe(registry.plugins.value)}")

    /** Takes [fake] out of [registry] as Remove does: its instance let go, its slot freed, its folders and keys deleted. */
    fun remove(fake: FakePlugin, registry: PluginRegistry = plugins.app) = runBlocking {
        withTimeoutOrNull(PLUGIN_WAIT_MS) { registry.remove(fake.id) } ?: throw AssertionError("${fake.id} not removed in time")
    }

    private fun takeOut() = runBlocking {
        val left = put.filter { (registry, fake) -> registry.plugins.value.orEmpty().any { it.id == fake.id } }
        put.clear()
        withTimeoutOrNull(PLUGIN_WAIT_MS) { left.forEach { (registry, fake) -> registry.remove(fake.id) } }
            ?: throw AssertionError("The fakes ${left.map { it.second.id }} not taken out in time")
    }
}

/** How many of [FakePluginRule]'s fakes the test starts with, in order; without it, all of them. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class FakeCount(val count: Int)
