package io.skjaere.usenet.plugincheck

import kotlin.metadata.KmClass
import kotlin.metadata.KmDeclarationContainer
import kotlin.metadata.KmValueParameter
import kotlin.metadata.Visibility
import kotlin.metadata.declaresDefaultValue
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile

/**
 * The dump of what a plugin can call of the plugin API or the UI kit (L625): the version, then every class a plugin can
 * name with its public and protected fields, constructors and methods, sorted, as their class files declare them. Checked
 * in beside each module, it makes any change to that surface fail the build until the dump is written again on purpose
 * (L614).
 *
 * Visibility is Kotlin's, as the binary-compatibility validator reads it: the class files make public much that Kotlin
 * keeps to its module, so a class or member that a class's @Metadata marks internal or private is left out (an internal
 * constructor, an internal top-level function), unless it is @PublishedApi. Left out too: names with a `$` (internal
 * members' names mangled with the module's, Compose's lambda getters and its `$stable` fields), Kotlin's synthetic
 * classes (lambdas, `when` mappings), anonymous and local classes, a member class of a class left out, Compose's
 * ComposableSingletons classes, and the module's R classes (resource ids, which no plugin reads, L580). Any of those may
 * change with no plugin noticing.
 *
 * Default arguments are part of what a built plugin calls, so the dump keeps them (L614):
 * - each function and constructor says which of its parameters have a default, `defaults(1,3)` by their place: a
 *   composable's are decided by place in a mask its own descriptor carries, so a default dropped while others stay
 *   changes no signature, only this note;
 * - the synthetic members a plugin's calls link to are listed, marked synthetic: the `$default` bridge a function's
 *   defaults add, the constructor with a DefaultConstructorMarker a constructor's defaults add (or that stands in for one
 *   taking an inline class), and an overload kept with @Deprecated(level = HIDDEN), which Kotlin compiles synthetic.
 *   Every other synthetic member (an accessor, a bridge, a lambda) stays out.
 */
object ApiDump {
    fun dump(version: String, classes: List<ByteArray>): String {
        val read = classes.map(::read).associateBy { it.name }
        val shown = read.values.filter { shows(it, read) }.sortedBy { it.name }
        return "$version\n" + shown.joinToString("\n") { cls -> "${header(cls)} {\n" + members(cls, read).joinToString("") { "\t$it\n" } + "}\n" }
    }

    /** [cls]'s members in the dump: its fields, its constructors, then its methods, each by name and descriptor. */
    private fun members(cls: ClassFile, read: Map<String, ClassFile>): List<String> {
        val order = compareBy<Member>({ it.name }, { it.descriptor })
        val fields = cls.fields.filter { shows(cls, it, field = true, read) }.sortedWith(order).map(::field)
        val (constructors, methods) = cls.methods.filter { shows(cls, it, field = false, read) }.sortedWith(order).partition { it.name == "<init>" }
        val declared = declarations(cls)
        return fields + (constructors + methods).map { method(it, defaults(it, declared)) }
    }

    /**
     * What differs between a checked-in dump ([expected]) and the classes' ([actual]): "removed: …" for each of the
     * former's lines the latter lacks, then "added: …" for each the other way round. A member is named with its class.
     */
    fun differences(expected: String, actual: String): List<String> {
        val before = entries(expected)
        val after = entries(actual)
        val kept = before.toSet() intersect after.toSet()
        return before.filterNot { it in kept }.map { "removed: $it" } + after.filterNot { it in kept }.map { "added: $it" }
    }

    /** The class files in [inputs]: class jars and folders of classes. */
    fun classFiles(inputs: List<File>): List<ByteArray> = inputs.flatMap { input ->
        if (input.isDirectory) {
            input.walk().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.path }.map { it.readBytes() }.toList()
        } else {
            ZipFile(input).use { zip ->
                zip.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                    .sortedBy { it.name }.map { entry -> zip.getInputStream(entry).use { it.readBytes() } }.toList()
            }
        }
    }.filterNot { it.isEmpty() }

    private fun entries(dump: String): List<String> {
        val lines = dump.lines()
        var owner = ""
        return listOf("version ${lines.first()}") + lines.drop(1).mapNotNull { line ->
            when {
                line.startsWith("\t") -> "$owner: ${line.trim()}"
                line.endsWith(" {") -> line.removeSuffix(" {").also { owner = it.substringAfter("class ").substringBefore(' ') }
                else -> null
            }
        }
    }

    private class Member(val access: Int, val name: String, val descriptor: String) {
        var publishedApi = false
    }

    private class ClassFile(
        val name: String,
        val access: Int,
        val superName: String?,
        val interfaces: List<String>,
    ) {
        /** Its own entry in its InnerClasses: the class it is a member of, and its access there. */
        var outer: String? = null
        var memberAccess: Int? = null
        var localOrAnonymous = false
        var metadata: KotlinClassMetadata? = null
        val fields = mutableListOf<Member>()
        val methods = mutableListOf<Member>()
    }

    private fun shows(cls: ClassFile, read: Map<String, ClassFile>): Boolean {
        val access = cls.memberAccess ?: cls.access
        val metadata = cls.metadata
        return access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0 &&
            access and Opcodes.ACC_SYNTHETIC == 0 &&
            !cls.localOrAnonymous &&
            cls.name.substringAfterLast('/').let { !it.startsWith("ComposableSingletons") && it != "R" && !it.startsWith("R$") } &&
            metadata !is KotlinClassMetadata.SyntheticClass &&
            (metadata !is KotlinClassMetadata.Class || metadata.kmClass.visibility.reachable) &&
            cls.outer?.let { outer -> read[outer]?.let { shows(it, read) } } != false
    }

    private fun shows(cls: ClassFile, member: Member, field: Boolean, read: Map<String, ClassFile>): Boolean {
        if (member.access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) == 0) return false
        if (field) {
            if (member.access and Opcodes.ACC_SYNTHETIC != 0 || '$' in member.name) return false
            return member.publishedApi || fieldVisibilities(cls, read)[member.name + member.descriptor]?.reachable ?: true
        }
        val declared = declarations(cls)
        if (member.access and Opcodes.ACC_SYNTHETIC != 0) return linked(cls, member, declared)
        if ('$' in member.name) return false
        return member.publishedApi || declared[member.name + member.descriptor]?.visibility?.reachable ?: true
    }

    /**
     * Whether a synthetic [member] is one a plugin's compiled call links to: a declaration's own (an overload hidden by
     * @Deprecated, a constructor that takes an inline class), or the bridge a declaration's defaults add.
     */
    private fun linked(cls: ClassFile, member: Member, declared: Map<String, Declared>): Boolean {
        declared[member.name + member.descriptor]?.let { return it.visibility.reachable }
        val params = member.descriptor.substringAfter('(').substringBefore(')')
        val returns = member.descriptor.substringAfter(')')
        val reachable = declared.values.filter { it.visibility.reachable }
        return when {
            // name$default(<the instance, for a member>, its parameters, one int mask per 32, an unused Object).
            member.name.endsWith(DEFAULT) -> reachable.any { d ->
                d.name == member.name.removeSuffix(DEFAULT) && d.defaults.isNotEmpty() && d.returns == returns &&
                    (masked(params, d.params, OBJECT) || masked(params.removePrefix("L${cls.name};"), d.params, OBJECT))
            }
            // <init>(its parameters, the masks, a DefaultConstructorMarker), or a constructor taking an inline class, whose
            // own is private: <init>(its parameters, a DefaultConstructorMarker).
            member.name == "<init>" && params.endsWith(MARKER) -> reachable.any { d ->
                d.name == "<init>" && (params == d.params.removeSuffix(MARKER) + MARKER || d.defaults.isNotEmpty() && masked(params, d.params.removeSuffix(MARKER), MARKER))
            }
            else -> false
        }
    }

    /** Whether [params] are [base]'s, then one or more int masks, then [end]. */
    private fun masked(params: String, base: String, end: String): Boolean {
        if (!params.startsWith(base) || !params.endsWith(end)) return false
        val masks = params.substring(base.length, params.length - end.length)
        return masks.isNotEmpty() && masks.all { it == 'I' }
    }

    /** A function, constructor or property accessor as Kotlin's metadata declares it: its JVM signature, and its defaults. */
    private class Declared(val name: String, val params: String, val returns: String, val visibility: Visibility, val defaults: List<Int>)

    /** What Kotlin's metadata says of [cls]'s methods, by JVM name and descriptor. */
    private fun declarations(cls: ClassFile): Map<String, Declared> {
        val container: KmDeclarationContainer = when (val metadata = cls.metadata) {
            is KotlinClassMetadata.Class -> metadata.kmClass
            is KotlinClassMetadata.FileFacade -> metadata.kmPackage
            is KotlinClassMetadata.MultiFileClassPart -> metadata.kmPackage
            else -> return emptyMap()
        }
        fun declared(name: String, descriptor: String, visibility: Visibility, parameters: List<KmValueParameter> = emptyList()) =
            name + descriptor to Declared(
                name, descriptor.substringAfter('(').substringBefore(')'), descriptor.substringAfter(')'), visibility,
                parameters.withIndex().filter { it.value.declaresDefaultValue }.map { it.index + 1 },
            )
        val constructors = (container as? KmClass)?.constructors.orEmpty()
            .mapNotNull { c -> c.signature?.let { declared(it.name, it.descriptor, c.visibility, c.valueParameters) } }
        val functions = container.functions.mapNotNull { f -> f.signature?.let { declared(it.name, it.descriptor, f.visibility, f.valueParameters) } }
        val accessors = container.properties.flatMap { p ->
            listOfNotNull(
                p.getterSignature?.let { declared(it.name, it.descriptor, p.getter.visibility) },
                p.setterSignature?.let { declared(it.name, it.descriptor, p.setter?.visibility ?: p.visibility) },
            )
        }
        return (constructors + functions + accessors).toMap()
    }

    /**
     * Which parameters of [member] have a default, by place: from its own declaration, or, for the constructor that stands in
     * for one taking an inline class, from that one's.
     */
    private fun defaults(member: Member, declared: Map<String, Declared>): List<Int> =
        declared[member.name + member.descriptor]?.defaults
            ?: if (member.name == "<init>" && member.descriptor.endsWith("$MARKER)V")) {
                declared["<init>(" + member.descriptor.substringAfter('(').removeSuffix("$MARKER)V") + ")V"]?.defaults.orEmpty()
            } else {
                emptyList()
            }

    /**
     * What Kotlin's metadata says of [cls]'s fields, by name and descriptor: its own properties', and its companion
     * object's, whose constants and @JvmField fields Kotlin puts in the class around it.
     */
    private fun fieldVisibilities(cls: ClassFile, read: Map<String, ClassFile>): Map<String, Visibility> {
        val kmClass = (cls.metadata as? KotlinClassMetadata.Class)?.kmClass
        val containers = when (val metadata = cls.metadata) {
            is KotlinClassMetadata.Class -> listOfNotNull(
                metadata.kmClass,
                kmClass?.companionObject?.let { (read["${cls.name}$$it"]?.metadata as? KotlinClassMetadata.Class)?.kmClass },
            )
            is KotlinClassMetadata.FileFacade -> listOf(metadata.kmPackage)
            is KotlinClassMetadata.MultiFileClassPart -> listOf(metadata.kmPackage)
            else -> emptyList()
        }
        return containers.flatMap { it.properties }.mapNotNull { p -> p.fieldSignature?.let { it.name + it.descriptor to p.visibility } }.toMap()
    }

    private val Visibility.reachable: Boolean get() = this == Visibility.PUBLIC || this == Visibility.PROTECTED

    private fun header(cls: ClassFile): String {
        val access = cls.memberAccess ?: cls.access
        val modifiers = listOfNotNull(
            if (access and Opcodes.ACC_PROTECTED != 0) "protected" else "public",
            "abstract".takeIf { access and Opcodes.ACC_ABSTRACT != 0 },
            "final".takeIf { access and Opcodes.ACC_FINAL != 0 },
            "enum".takeIf { access and Opcodes.ACC_ENUM != 0 },
            "annotation".takeIf { access and Opcodes.ACC_ANNOTATION != 0 },
            "interface".takeIf { access and Opcodes.ACC_INTERFACE != 0 },
        )
        val supertypes = listOfNotNull(cls.superName?.takeIf { it != "java/lang/Object" }) + cls.interfaces.sorted()
        return "${modifiers.joinToString(" ")} class ${cls.name}" + if (supertypes.isEmpty()) "" else " : ${supertypes.joinToString(", ")}"
    }

    private fun field(member: Member): String = modifiers(member) + "field ${member.name} ${member.descriptor}"

    private fun method(member: Member, defaults: List<Int>): String =
        modifiers(member) + (if (member.name == "<init>") "<init> ${member.descriptor}" else "fun ${member.name} ${member.descriptor}") +
            if (defaults.isEmpty()) "" else " defaults(${defaults.joinToString(",")})"

    private fun modifiers(member: Member): String = listOfNotNull(
        if (member.access and Opcodes.ACC_PROTECTED != 0) "protected" else "public",
        "static".takeIf { member.access and Opcodes.ACC_STATIC != 0 },
        "abstract".takeIf { member.access and Opcodes.ACC_ABSTRACT != 0 },
        "final".takeIf { member.access and Opcodes.ACC_FINAL != 0 },
        "synthetic".takeIf { member.access and Opcodes.ACC_SYNTHETIC != 0 },
    ).joinToString(" ", postfix = " ")

    private fun read(bytes: ByteArray): ClassFile {
        lateinit var cls: ClassFile
        val metadata = mutableMapOf<String, Any>()
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<String>?) {
                    cls = ClassFile(name, access, superName, interfaces.orEmpty().toList())
                }

                override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? =
                    if (descriptor == "Lkotlin/Metadata;") MetadataReader(metadata) else null

                override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                    if (name != cls.name) return
                    cls.outer = outerName
                    cls.memberAccess = access
                    cls.localOrAnonymous = outerName == null || innerName == null
                }

                override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                    cls.fields += Member(access, name, descriptor)
                    return null
                }

                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor {
                    val member = Member(access, name, descriptor).also { cls.methods += it }
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                            if (descriptor == "Lkotlin/PublishedApi;") member.publishedApi = true
                            return null
                        }
                    }
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        if (metadata.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            cls.metadata = KotlinClassMetadata.readStrict(
                Metadata(
                    kind = metadata["k"] as Int?,
                    metadataVersion = metadata["mv"] as IntArray?,
                    data1 = (metadata["d1"] as List<String>?)?.toTypedArray(),
                    data2 = (metadata["d2"] as List<String>?)?.toTypedArray(),
                    extraString = metadata["xs"] as String?,
                    packageName = metadata["pn"] as String?,
                    extraInt = metadata["xi"] as Int?,
                ),
            )
        }
        return cls
    }

    private const val DEFAULT = "\$default"
    private const val OBJECT = "Ljava/lang/Object;"
    private const val MARKER = "Lkotlin/jvm/internal/DefaultConstructorMarker;"

    /** kotlin.Metadata's arguments as ASM reads them: numbers and int arrays as they are, string arrays as lists. */
    private class MetadataReader(private val into: MutableMap<String, Any>) : AnnotationVisitor(Opcodes.ASM9) {
        override fun visit(name: String, value: Any) {
            into[name] = value
        }

        override fun visitArray(name: String): AnnotationVisitor {
            val values = mutableListOf<String>().also { into[name] = it }
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any) {
                    values += value as String
                }
            }
        }
    }
}
