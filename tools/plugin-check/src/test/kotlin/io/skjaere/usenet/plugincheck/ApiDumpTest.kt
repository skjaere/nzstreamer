package io.skjaere.usenet.plugincheck

import io.skjaere.usenet.plugincheck.fixture.Harbor
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiDumpTest {

    // The fixture package's class files as the test compile wrote them, a ComposableSingletons class as Compose's compiler
    // makes one, and an R class as a library's build makes one: public, with public members, no metadata of Kotlin's.
    private val classes: List<ByteArray> =
        File(Harbor::class.java.protectionDomain.codeSource.location.toURI()).resolve("io/skjaere/usenet/plugincheck/fixture")
            .walk().filter { it.isFile && it.extension == "class" }.sortedBy { it.name }.map { it.readBytes() }.toList() +
            composableSingletons() + fonts()

    private fun dump(classes: List<ByteArray> = this.classes) = ApiDump.dump("1.0", classes)

    @Test
    fun `public and protected members are listed, as the plugin API's dump writes them`() {
        assertEquals(
            """
            1.0
            public class io/skjaere/usenet/plugincheck/fixture/Harbor {
            	public static final field Companion Lio/skjaere/usenet/plugincheck/fixture/Harbor${'$'}Companion;
            	public static final field PIER I
            	public <init> ()V
            	public final fun getName ()Ljava/lang/String;
            	public final fun getTide ()I
            	protected fun moor ()Ljava/lang/String;
            	public final fun sail (I)I
            }

            public final class io/skjaere/usenet/plugincheck/fixture/Harbor${'$'}Companion {
            }

            public final class io/skjaere/usenet/plugincheck/fixture/HarborKt {
            	public static final fun beacon ()Ljava/lang/String;
            }

            """.trimIndent(),
            dump(),
        )
    }

    @Test
    fun `private, synthetic and internal members, an internal top-level function, a mangled accessor and ComposableSingletons are left out`() {
        val dump = dump()
        listOf("hidden", "keeper", "sail\$default", "setTide", "lighthouse", "Keeper", "ComposableSingletons", "getLambda", "(Ljava/lang/String;)V", "fixture/R")
            .forEach { assertTrue(it !in dump, "$it is in the dump:\n$dump") }
    }

    @Test
    fun `the dump is sorted and stable`() {
        assertEquals(dump(), dump(classes.reversed()))
        assertEquals(dump(), dump())
    }

    @Test
    fun `the first line is the version`() {
        assertEquals("1.0", dump().lineSequence().first())
        assertEquals("1.1", ApiDump.dump("1.1", classes).lineSequence().first())
    }

    @Test
    fun `a removed member fails the dump's check, and an unchanged dump passes`() {
        val checkedIn = dump().replace("\tpublic final fun sail (I)I\n", "\tpublic final fun moorAt (I)V\n\tpublic final fun sail (I)I\n")
        assertEquals(
            listOf("removed: io/skjaere/usenet/plugincheck/fixture/Harbor: public final fun moorAt (I)V"),
            ApiDump.differences(expected = checkedIn, actual = dump()),
        )
        assertEquals(
            listOf("added: io/skjaere/usenet/plugincheck/fixture/Harbor: public final fun moorAt (I)V"),
            ApiDump.differences(expected = dump(), actual = checkedIn),
        )
        assertEquals(listOf("removed: version 1.0", "added: version 1.1"), ApiDump.differences(dump(), ApiDump.dump("1.1", classes)))
        assertEquals(emptyList(), ApiDump.differences(dump(), dump()))
    }

    private companion object {
        fun composableSingletons(): ByteArray = ClassWriter(0).apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "io/skjaere/usenet/plugincheck/fixture/ComposableSingletons\$HarborKt", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "INSTANCE", "Lio/skjaere/usenet/plugincheck/fixture/ComposableSingletons\$HarborKt;", null, null).visitEnd()
            visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "getLambda1", "()Lkotlin/jvm/functions/Function2;", null, null).visitEnd()
            visitEnd()
        }.toByteArray()

        fun fonts(): ByteArray = ClassWriter(0).apply {
            visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "io/skjaere/usenet/plugincheck/fixture/R\$font", null, "java/lang/Object", null)
            visitField(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "sora", "I", null, null).visitEnd()
            visitEnd()
        }.toByteArray()
    }
}
