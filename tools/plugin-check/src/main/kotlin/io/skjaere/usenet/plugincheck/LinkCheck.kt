package io.skjaere.usenet.plugincheck

import io.skjaere.usenet.apicheck.ApiDatabase
import io.skjaere.usenet.apicheck.DexClass
import io.skjaere.usenet.apicheck.Reference
import io.skjaere.usenet.apicheck.SdkStubs

/**
 * The link check (L625): every class, method and field a plugin names outside its package must be there when NZStreamer
 * loads it, in the host's dex ([host], the release build's, which R8 leaves whole) or in android.jar ([sdk]) at
 * [minSdk]. Members are found as the runtime finds them, through the class's supertypes, from the host's classes into
 * android.jar's; android.jar's carry the API level api-versions.xml ([api]) gives them, and one it doesn't list (a hidden
 * superclass's) counts as there, as lint counts it. Run against every published plugin, it fails the host change that
 * would break one with NoSuchMethodError, before that change is released.
 */
class LinkCheck(host: List<DexClass>, private val sdk: SdkStubs, private val api: ApiDatabase, private val minSdk: Int) {
    private val host = host.associateBy { it.name }

    fun check(id: String, plugin: List<DexClass>): List<String> {
        val own = "app/nzstreamer/plugin/$id/"
        // The plugin's own classes come with it: only what it names outside its package is looked up.
        val found = plugin.filter { it.name.startsWith(own) }.flatMap { cls ->
            cls.references.filterNot { owner(it).startsWith(own) }.mapNotNull { missing(cls, it) }
        }
        return found.groupingBy { it }.eachCount().map { (text, count) -> if (count > 1) "$text ($count references)" else text }.sorted()
    }

    private fun owner(reference: Reference): String = when (reference) {
        is Reference.Type -> reference.name
        is Reference.Method -> reference.owner
        is Reference.Field -> reference.owner
    }

    private fun missing(cls: DexClass, reference: Reference): String? {
        val owner = owner(reference)
        if (owner.startsWith("[")) return null
        val where = listOfNotNull(cls.name.replace('/', '.'), reference.method).joinToString(".")
        val (named, level) = when (reference) {
            is Reference.Type -> dotted(owner) to classLevel(owner)
            is Reference.Method -> "${dotted(owner)}#${reference.name}${reference.descriptor}" to
                memberLevel(owner, Member(reference.name + reference.descriptor, isField = false))
            is Reference.Field -> "${dotted(owner)}#${reference.name}" to memberLevel(owner, Member(reference.name, isField = true))
        }
        return when {
            level == null -> "$where: $named isn't in NZStreamer's build"
            level > minSdk -> "$where: $named needs API $level, above minSdk $minSdk"
            else -> null
        }
    }

    /** A method (name and descriptor) or a field (name). Constructors aren't inherited: only their own class has them. */
    private class Member(val key: String, val isField: Boolean) {
        val inherited: Boolean get() = isField || !key.startsWith("<init>")
    }

    /** The API level at which [className] is there: 0 for the host's own, null where neither the host nor android.jar has it. */
    private fun classLevel(className: String): Int? = when {
        className in host -> 0
        sdk.hasClass(className) -> api[className]?.since ?: 0
        else -> null
    }

    /**
     * The lowest API level at which [member] is reachable through [className]: 0 when the host declares it on the way,
     * android.jar's level for it otherwise, null when nothing on the way declares it.
     */
    private fun memberLevel(className: String, member: Member, reachedAt: Int = 0): Int? {
        host[className]?.let { cls ->
            val declared = if (member.isField) member.key in cls.fields else member.key in cls.methods
            return when {
                declared -> reachedAt
                !member.inherited -> null
                else -> cls.supertypes.mapNotNull { memberLevel(it, member, reachedAt) }.minOrNull()
            }
        }
        if (!sdk.hasClass(className)) return null
        val apiClass = api[className] ?: return reachedAt.takeIf { inStubs(className, member) }
        val level = maxOf(reachedAt, apiClass.since)
        val own = (if (member.isField) apiClass.fields[member.key] else apiClass.methods[member.key])?.let { maxOf(level, it) }
        val inherited = if (member.inherited) {
            apiClass.supertypes.ifEmpty { listOf(ApiDatabase.Supertype(OBJECT, 1)) }.filter { it.name != className }
                .mapNotNull { memberLevel(it.name, member, maxOf(level, it.since)) }
        } else {
            emptyList()
        }
        return (listOfNotNull(own) + inherited).minOrNull() ?: level.takeIf { inStubs(className, member) }
    }

    // android.jar has members api-versions.xml leaves out with the hidden classes that declare them.
    private fun inStubs(className: String, member: Member): Boolean =
        if (member.isField) sdk.hasField(className, member.key) else sdk.hasMethod(className, member.key, member.inherited)

    private fun dotted(internalName: String) = internalName.replace('/', '.')

    private companion object {
        const val OBJECT = "java/lang/Object"
    }
}
