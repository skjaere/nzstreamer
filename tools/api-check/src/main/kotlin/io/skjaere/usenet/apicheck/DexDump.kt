package io.skjaere.usenet.apicheck

/**
 * One class from a `dexdump -d` listing, with internal names (`io/skjaere/Foo`). [methods] are
 * keyed by name + descriptor and [fields] by name, like [ApiDatabase]. [references] is only
 * filled for the classes the parser was asked to scan.
 */
data class DexClass(
    val name: String,
    val superclass: String?,
    val interfaces: List<String>,
    val methods: Set<String>,
    val fields: Set<String>,
    val references: List<Reference>,
) {
    val supertypes: List<String> get() = listOfNotNull(superclass) + interfaces
}

/** Something the code of [DexClass] uses. [method] is the using method, null for its own supertypes. */
sealed interface Reference {
    val method: String?

    /** A class: a supertype, a caught exception, or the operand of new-instance, check-cast, const-class and the like. */
    data class Type(override val method: String?, val name: String) : Reference

    data class Method(override val method: String, val owner: String, val name: String, val descriptor: String) : Reference

    data class Field(override val method: String, val owner: String, val name: String) : Reference
}

/** Reads `dexdump -d` output (with or without `-n`). */
object DexDumpParser {
    private val classDescriptor = Regex("""^\s+Class descriptor\s+: '(L[^']+;)'""")
    private val superclassLine = Regex("""^\s+Superclass\s+: '(L[^']+;)'""")
    private val interfaceLine = Regex("""^\s+#\d+\s+: '(L[^']+;)'""")
    private val nameLine = Regex("""^\s+name\s+: '(.*)'$""")
    private val typeLine = Regex("""^\s+type\s+: '(.*)'$""")
    private val instruction = Regex("""\|[0-9a-f]{4,}: (\S+) ?(.*)$""")
    private val catchLine = Regex("""^\s+(L[^;\s]+;) -> 0x""")
    private val memberOperand = Regex("""(\[*(?:L[^;\s]+;|[ZBSCIJFD]))\.([^:\s]+):([^\s,]+)""")
    private val typeOperand = Regex("""\[*L([^;\s]+);""")
    private val fieldOpcode = Regex("""[is](get|put)(-\w+)?""")
    private val typeOpcodes = setOf(
        "new-instance", "check-cast", "const-class", "instance-of", "new-array",
        "filled-new-array", "filled-new-array/range",
    )

    private enum class Section { None, Interfaces, Fields, Methods }

    private class ClassBuilder(val name: String, val scanned: Boolean) {
        var superclass: String? = null
        val interfaces = mutableListOf<String>()
        val methods = mutableSetOf<String>()
        val fields = mutableSetOf<String>()
        val references = mutableListOf<Reference>()
        var section = Section.None
        var pendingName: String? = null
        /** The method whose code is being listed. */
        var method: String? = null

        fun build(): DexClass {
            val supertypes = if (scanned) (listOfNotNull(superclass) + interfaces).map { Reference.Type(null, it) } else emptyList()
            return DexClass(name, superclass, interfaces, methods, fields, supertypes + references)
        }
    }

    /** Parses every class; only those [scan] accepts get their [DexClass.references] collected. */
    fun parse(lines: Sequence<String>, scan: (className: String) -> Boolean): List<DexClass> {
        val classes = mutableListOf<DexClass>()
        var current: ClassBuilder? = null
        lines.forEach { line ->
            val descriptor = classDescriptor.find(line)
            if (descriptor != null) {
                current?.let { classes += it.build() }
                val name = internalName(descriptor.groupValues[1])
                current = ClassBuilder(name, scan(name))
            } else {
                current?.parseLine(line)
            }
        }
        current?.let { classes += it.build() }
        return classes
    }

    private fun ClassBuilder.parseLine(line: String) {
        when {
            line.startsWith("  Superclass ") -> superclass = superclassLine.find(line)?.let { internalName(it.groupValues[1]) }
            line.startsWith("  Interfaces ") -> section = Section.Interfaces
            line.startsWith("  Static fields ") || line.startsWith("  Instance fields ") -> section = Section.Fields
            line.startsWith("  Direct methods ") || line.startsWith("  Virtual methods ") -> section = Section.Methods
            line.startsWith("  source_file_idx") -> section = Section.None
            section == Section.Interfaces -> interfaceLine.find(line)?.let { interfaces += internalName(it.groupValues[1]) }
            section == Section.Fields -> nameLine.find(line)?.let { fields += it.groupValues[1] }
            section == Section.Methods -> parseMethodLine(line)
        }
    }

    private fun ClassBuilder.parseMethodLine(line: String) {
        nameLine.find(line)?.let { pendingName = it.groupValues[1]; return }
        typeLine.find(line)?.let { type ->
            method = pendingName
            methods += pendingName + type.groupValues[1]
            return
        }
        val inMethod = method?.takeIf { scanned } ?: return
        val code = instruction.find(line)
        val reference = if (code != null) {
            reference(inMethod, opcode = code.groupValues[1], operands = code.groupValues[2].substringBefore(" // "))
        } else {
            catchLine.find(line)?.let { Reference.Type(inMethod, internalName(it.groupValues[1])) }
        }
        reference?.let { references += it }
    }

    private fun reference(method: String, opcode: String, operands: String): Reference? = when {
        opcode.startsWith("invoke-") -> memberOperand.find(operands)?.let { m ->
            Reference.Method(method, internalName(m.groupValues[1]), m.groupValues[2], m.groupValues[3])
        }
        fieldOpcode.matches(opcode) -> memberOperand.find(operands)?.let { m ->
            Reference.Field(method, internalName(m.groupValues[1]), m.groupValues[2])
        }
        opcode in typeOpcodes -> typeOperand.findAll(operands).lastOrNull()?.let { Reference.Type(method, it.groupValues[1]) }
        else -> null
    }

    /** `Ljava/io/InputStream;` -> `java/io/InputStream`; array and primitive descriptors stay as they are. */
    private fun internalName(descriptor: String) =
        if (descriptor.startsWith("L") && descriptor.endsWith(";")) descriptor.substring(1, descriptor.length - 1) else descriptor
}
