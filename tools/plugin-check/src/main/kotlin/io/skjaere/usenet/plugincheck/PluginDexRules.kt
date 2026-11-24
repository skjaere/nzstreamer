package io.skjaere.usenet.plugincheck

import io.skjaere.usenet.apicheck.DexClass
import io.skjaere.usenet.apicheck.Reference
import java.io.File
import java.util.zip.ZipFile

/**
 * What a plugin may take from NZStreamer at run time (L615): the plugin API's and the UI kit's classes, by name from their
 * class jars, and the shared libraries, by package. The kit by name, not by package: its packages are the app's too, and
 * a package would let a plugin name any class of the app's in them.
 */
class SharedClasses(private val classes: Set<String>) {
    operator fun contains(name: String): Boolean = name in classes || LIBRARY_PACKAGES.any(name::startsWith)

    companion object {
        /**
         * L615's libraries: Kotlin's standard library, kotlinx-coroutines, kotlinx-serialization-json, Compose from the BOM
         * (runtime, ui, foundation, material3 and material-icons-core, with the animation and ripple the BOM brings them),
         * lifecycle-viewmodel-compose, activity-compose and slf4j-api. Never ktor (L631).
         */
        val LIBRARY_PACKAGES = listOf(
            "kotlin/", "kotlinx/coroutines/", "kotlinx/serialization/", "androidx/compose/", "androidx/lifecycle/", "androidx/activity/",
            "org/slf4j/",
        )

        /** The classes in [jars] (the API's and the kit's class jars), by internal name. */
        fun fromJars(jars: List<File>): SharedClasses = SharedClasses(
            jars.flatMap { jar ->
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.map { it.removeSuffix(".class") }.toList()
                }
            }.toSet(),
        )
    }
}

/**
 * A plugin APK's dex and entries, against the rules every plugin keeps (L586, L625):
 * - every class in its dex is under app.nzstreamer.plugin.<id>: the shared libraries, the API and the kit come from
 *   NZStreamer, compileOnly, and nothing else is bundled;
 * - outside its package it names only android.*, java.*, the shared libraries and the API's and the kit's classes, so
 *   nothing of NZStreamer's own beyond them ([SharedClasses]);
 * - no android.util.Log, System.out or System.err and no Throwable.printStackTrace(): each reaches logcat unredacted,
 *   around slf4j, which redacts;
 * - no class that declares Parcelable or Serializable among its own interfaces: after a process death the host restores
 *   saved state with its own class loader, which can't make a plugin's types. Only its own interfaces: every suspend
 *   lambda inherits Serializable from Kotlin's base classes, and every enum from java.lang.Enum;
 * - no R class, no lib/ entry and no res/ entry: plugins carry no resources (L580) and no native library.
 *
 * Each violation is reported once, with how many references it has.
 */
class PluginDexRules(id: String, private val shared: SharedClasses) {
    private val packageName = "app.nzstreamer.plugin.$id"
    private val own = "app/nzstreamer/plugin/$id/"

    fun check(classes: List<DexClass>, entries: List<String>): List<String> {
        val found = classes.flatMap { cls ->
            if (cls.name.startsWith(own)) {
                listOfNotNull(rClass(cls), declaresSaved(cls)) + cls.references.mapNotNull { reference(cls, it) }
            } else {
                listOf(carried(cls.name))
            }
        } + entries.mapNotNull(::entry)
        return found.groupingBy { it }.eachCount().map { (text, count) -> if (count > 1) "$text ($count references)" else text }.sorted()
    }

    private fun carried(name: String): String = when {
        isR(name) -> "carries ${dotted(name)}, an R class: plugins have no resources"
        name in shared -> "carries ${dotted(name)}, which NZStreamer gives every plugin: take its library compileOnly"
        else -> "carries ${dotted(name)}, outside $packageName"
    }

    private fun rClass(cls: DexClass): String? = "carries ${dotted(cls.name)}, an R class: plugins have no resources".takeIf { isR(cls.name) }

    private fun declaresSaved(cls: DexClass): String? = cls.interfaces.firstOrNull { it in SAVED }?.let {
        "${dotted(cls.name)} declares ${dotted(it)}: after a process death the host can't make a plugin's types again"
    }

    private fun reference(cls: DexClass, reference: Reference): String? {
        val where = listOfNotNull(dotted(cls.name), reference.method).joinToString(".")
        val owner = when (reference) {
            is Reference.Type -> reference.name
            is Reference.Method -> reference.owner
            is Reference.Field -> reference.owner
        }
        val named = when (reference) {
            is Reference.Type -> dotted(reference.name)
            is Reference.Method -> "${dotted(reference.owner)}#${reference.name}${reference.descriptor}"
            is Reference.Field -> "${dotted(reference.owner)}#${reference.name}"
        }
        return when {
            owner.startsWith("[") || owner.startsWith(own) -> null
            owner == LOG -> "$where: $named, whose lines reach logcat unredacted: log through slf4j"
            reference is Reference.Field && owner == SYSTEM && reference.name in setOf("out", "err") ||
                reference is Reference.Method && reference.name == "printStackTrace" && reference.descriptor == "()V" ->
                "$where: $named, which reaches logcat unredacted: log through slf4j"
            isR(owner) -> "$where: ${dotted(owner)}, an R class: plugins use no resources"
            owner in shared || owner.startsWith("android/") || owner.startsWith("java/") -> null
            owner.startsWith(NZSTREAMER) -> "$where: ${dotted(owner)}, NZStreamer's own outside the plugin API and the UI kit"
            else -> "$where: ${dotted(owner)}, which NZStreamer doesn't share with plugins"
        }
    }

    private fun entry(name: String): String? = when {
        name.startsWith("lib/") -> "$name: plugins carry no native library"
        name.startsWith("res/") -> "$name: plugins carry no resources"
        else -> null
    }

    private fun isR(name: String): Boolean = name.substringAfterLast('/').let { it == "R" || it.startsWith("R$") }

    private fun dotted(internalName: String) = internalName.replace('/', '.')

    private companion object {
        const val LOG = "android/util/Log"
        const val SYSTEM = "java/lang/System"
        const val NZSTREAMER = "io/skjaere/usenet/"
        val SAVED = setOf("android/os/Parcelable", "java/io/Serializable")
    }
}
