package io.skjaere.usenet.apicheck

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile

/**
 * The class files of the SDK's android.jar. api-versions.xml drops hidden superclasses together
 * with the members they declare (StringBuilder.setLength and indexOf live in the package-private
 * AbstractStringBuilder), so a member it does not list may still exist; android.jar settles that.
 * It carries no API levels, so a member found only here counts as available, as it does in lint.
 */
class SdkStubs(private val classFile: (className: String) -> ByteArray?) {

    private class Stub(val supertypes: List<String>, val methods: Set<String>, val fields: Set<String>)

    private val stubs = mutableMapOf<String, Stub?>()

    fun hasClass(className: String) = stub(className) != null

    /** [key] is name + descriptor. Constructors are not inherited, so pass [inherited] = false for them. */
    fun hasMethod(className: String, key: String, inherited: Boolean = true): Boolean =
        stub(className)?.let { stub -> key in stub.methods || (inherited && stub.supertypes.any { hasMethod(it, key) }) } == true

    fun hasField(className: String, name: String): Boolean =
        stub(className)?.let { stub -> name in stub.fields || stub.supertypes.any { hasField(it, name) } } == true

    private fun stub(className: String): Stub? =
        if (className in stubs) stubs[className] else classFile(className)?.let(::read).also { stubs[className] = it }

    private fun read(bytes: ByteArray): Stub {
        val reader = ClassReader(bytes)
        val methods = mutableSetOf<String>()
        val fields = mutableSetOf<String>()
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<String>?): MethodVisitor? {
                    methods += name + descriptor
                    return null
                }

                override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                    fields += name
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
        return Stub(listOfNotNull(reader.superName) + reader.interfaces, methods, fields)
    }

    companion object {
        val NONE = SdkStubs { null }

        /** Reads classes from [jar] on demand; the file stays open for the life of the process. */
        fun open(jar: File): SdkStubs {
            val zip = ZipFile(jar)
            return SdkStubs { name -> zip.getEntry("$name.class")?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } } }
        }
    }
}
