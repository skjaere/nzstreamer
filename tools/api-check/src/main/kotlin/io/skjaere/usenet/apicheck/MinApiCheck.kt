package io.skjaere.usenet.apicheck

/**
 * A use of a JDK API that [minSdk][MinApiChecker] devices lack. [apiLevel] is null when the
 * Android SDK has no such class or member at all. [reference] is shown as written in the dex
 * (`java.io.InputStream#readNBytes([BII)I`); [declaredIn] names the JDK class that declares an
 * inherited member when the dex refers to it through a subclass.
 */
data class Violation(
    val className: String,
    val method: String?,
    val reference: String,
    val declaredIn: String?,
    val apiLevel: Int?,
) {
    val location: String get() = listOfNotNull(className, method).joinToString(".")

    fun describe(): String {
        val inherited = declaredIn?.let { " (declared in $it)" }.orEmpty()
        val requirement = apiLevel?.let { "needs API $it" } ?: "is not in the Android SDK"
        return "$location: $reference$inherited $requirement"
    }
}

/**
 * Accepted violations, one per line: `<referencing class or class.method> <reference>`, as
 * [Violation] prints them. A `#` at the start of a line or after whitespace starts a comment;
 * every entry should say why the use is safe.
 */
class Allowlist(private val entries: Set<Pair<String, String>>) {
    operator fun contains(violation: Violation) =
        listOf(violation.className, violation.location).any { it to violation.reference in entries }

    companion object {
        val EMPTY = Allowlist(emptySet())
        private val COMMENT = Regex("""(^|\s)#.*""")
        private val WHITESPACE = Regex("""\s+""")

        fun parse(text: String) = Allowlist(
            text.lineSequence()
                .map { it.replace(COMMENT, "").trim() }
                .filter { it.isNotEmpty() }
                .map { line ->
                    val fields = line.split(WHITESPACE)
                    require(fields.size == 2) { "Bad allowlist line: $line" }
                    fields[0] to fields[1]
                }
                .toSet(),
        )
    }
}

/**
 * Finds uses of JDK classes and members (java, javax, jdk, sun) that devices at [minSdk] lack.
 * Members are resolved the way the runtime does, through the class hierarchy: a call through a
 * subclass (`ByteArrayInputStream.readNBytes`, or an app class extending InputStream) needs the
 * level of the JDK class that declares it. android.* is left to lint, which understands SDK_INT
 * guards; this check cannot, so a guarded use has to be allowlisted. [sdk] (android.jar) only
 * decides whether something api-versions.xml does not list exists at all.
 */
class MinApiChecker(
    private val api: ApiDatabase,
    private val minSdk: Int,
    private val allowlist: Allowlist = Allowlist.EMPTY,
    private val sdk: SdkStubs = SdkStubs.NONE,
) {
    /** Every offending reference in the [DexClass.references] of [classes], once per occurrence. */
    fun check(classes: List<DexClass>): List<Violation> {
        val dex = classes.associateBy { it.name }
        return classes.asSequence()
            .flatMap { cls -> cls.references.asSequence().mapNotNull { violation(dex, cls, it) } }
            .filterNot { it in allowlist }
            .toList()
    }

    private fun violation(dex: Map<String, DexClass>, cls: DexClass, reference: Reference): Violation? {
        fun found(text: String, declaredIn: String?, level: Int?) =
            Violation(dotted(cls.name), reference.method, text, declaredIn?.let(::dotted), level)
        return when (reference) {
            is Reference.Type -> classLevel(reference.name)?.takeIf { it > minSdk }?.let { level ->
                found(dotted(reference.name), null, level.takeIf { it != MISSING })
            }
            is Reference.Method -> memberViolation(dex, reference.owner, Member.Method(reference.name + reference.descriptor)) { declaredIn, level ->
                found("${dotted(reference.owner)}#${reference.name}${reference.descriptor}", declaredIn, level)
            }
            is Reference.Field -> memberViolation(dex, reference.owner, Member.Field(reference.name)) { declaredIn, level ->
                found("${dotted(reference.owner)}#${reference.name}", declaredIn, level)
            }
        }
    }

    private sealed interface Member {
        val key: String
        fun levelIn(cls: ApiDatabase.ApiClass): Int? = when (this) {
            is Method -> cls.methods[key]
            is Field -> cls.fields[key]
        }
        fun isDeclaredBy(cls: DexClass): Boolean = when (this) {
            is Method -> key in cls.methods
            is Field -> key in cls.fields
        }
        fun existsIn(sdk: SdkStubs, className: String): Boolean = when (this) {
            is Method -> sdk.hasMethod(className, key, inherited = !isConstructor)
            is Field -> sdk.hasField(className, key)
        }

        data class Method(override val key: String) : Member {
            /** Constructors are not inherited: only the owner itself can declare one. */
            val isConstructor get() = key.startsWith("<init>")
        }

        data class Field(override val key: String) : Member
    }

    /** A class or interface that declares a member, and the API level at which it is reachable there. */
    private data class Declaration(val className: String, val level: Int)

    private fun memberViolation(
        dex: Map<String, DexClass>,
        owner: String,
        member: Member,
        violation: (declaredIn: String?, level: Int?) -> Violation,
    ): Violation? {
        if (owner.startsWith("[")) return null
        if (classLevel(owner) == MISSING) return violation(null, null)
        val declarations = declarations(dex, owner, member, reachedAt = 0)
        if (declarations.isEmpty()) return violation(null, null).takeUnless { inSdkStubs(dex, owner, member) }
        val earliest = declarations.minBy { it.level }
        return violation(earliest.className.takeIf { it != owner }, earliest.level).takeIf { earliest.level > minSdk }
    }

    /**
     * Where in [className]'s hierarchy [member] is declared. Dex classes always have their own
     * members; android.* and classes outside both the dex and the SDK count as available here.
     * An empty result means only JDK classes are in the way and none of them declares it.
     */
    private fun declarations(dex: Map<String, DexClass>, className: String, member: Member, reachedAt: Int): List<Declaration> {
        val dexClass = dex[className]
        val apiClass = api[className]
        val inheritable = member !is Member.Method || !member.isConstructor
        return when {
            dexClass != null -> when {
                member.isDeclaredBy(dexClass) || !inheritable -> listOf(Declaration(className, 0))
                else -> dexClass.supertypes.flatMap { declarations(dex, it, member, reachedAt) }
            }
            !isJdk(className) -> listOf(Declaration(className, 0))
            apiClass == null -> emptyList()
            else -> {
                val level = maxOf(reachedAt, apiClass.since)
                val own = member.levelIn(apiClass)?.let { Declaration(className, maxOf(level, it)) }
                val inherited = if (inheritable) {
                    (apiClass.supertypes.ifEmpty { listOf(OBJECT) })
                        .filter { it.name != className }
                        .flatMap { declarations(dex, it.name, member, maxOf(level, it.since)) }
                } else {
                    emptyList()
                }
                listOfNotNull(own) + inherited
            }
        }
    }

    /** Whether android.jar has [member] in the JDK classes above [className], which api-versions.xml did not place. */
    private fun inSdkStubs(dex: Map<String, DexClass>, className: String, member: Member): Boolean =
        dex[className]?.supertypes?.any { inSdkStubs(dex, it, member) } ?: member.existsIn(sdk, className)

    /** API level of a JDK class, [MISSING] if Android lacks it, null if it is not a JDK class or its level is unknown. */
    private fun classLevel(className: String): Int? = when {
        className.startsWith("[") || !isJdk(className) -> null
        else -> api[className]?.since ?: MISSING.takeUnless { sdk.hasClass(className) }
    }

    private fun isJdk(className: String) = JDK_PACKAGES.any { className.startsWith(it) }

    private fun dotted(internalName: String) = internalName.replace('/', '.')

    private companion object {
        val JDK_PACKAGES = listOf("java/", "javax/", "jdk/", "sun/")
        val OBJECT = ApiDatabase.Supertype("java/lang/Object", 1)
        const val MISSING = Int.MAX_VALUE
    }
}
