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
            	public final synthetic fun dock (I)Ljava/lang/String;
            	public final fun dock (ILjava/lang/String;)Ljava/lang/String; defaults(1,2)
            	public static synthetic fun dock${'$'}default (Lio/skjaere/usenet/plugincheck/fixture/Harbor;ILjava/lang/String;ILjava/lang/Object;)Ljava/lang/String;
            	public final fun getName ()Ljava/lang/String;
            	public final fun getTide ()I
            	protected fun moor ()Ljava/lang/String;
            	public final fun sail (I)I defaults(1)
            	public static synthetic fun sail${'$'}default (Lio/skjaere/usenet/plugincheck/fixture/Harbor;IILjava/lang/Object;)I
            }

            public final class io/skjaere/usenet/plugincheck/fixture/Harbor${'$'}Companion {
            }

            public final class io/skjaere/usenet/plugincheck/fixture/HarborKt {
            	public static final fun beacon ()Ljava/lang/String;
            }

            public final class io/skjaere/usenet/plugincheck/fixture/Pier {
            	public <init> ()V
            	public <init> (ILjava/lang/String;)V defaults(1,2)
            	public synthetic <init> (ILjava/lang/String;ILkotlin/jvm/internal/DefaultConstructorMarker;)V
            	public final fun getLength ()I
            	public final fun getName ()Ljava/lang/String;
            }

            """.trimIndent(),
            dump(),
        )
    }

    @Test
    fun `private, synthetic and internal members, an internal top-level function, a mangled accessor and ComposableSingletons are left out`() {
        val dump = dump()
        listOf("hidden", "keeper", "setTide", "lighthouse", "Keeper", "ComposableSingletons", "getLambda", "(Ljava/lang/String;)V", "fixture/R")
            .forEach { assertTrue(it !in dump, "$it is in the dump:\n$dump") }
    }

    @Test
    fun `a default argument dropped, or a hidden overload, is a difference the check finds`() {
        // dock loses its crew's default: both of dock's signatures stay, and a plugin built against the dump would pass
        // the crew's bit in a mask the new body no longer reads.
        val dropped = dump().replace("dock (ILjava/lang/String;)Ljava/lang/String; defaults(1,2)", "dock (ILjava/lang/String;)Ljava/lang/String; defaults(1)")
        assertEquals(
            listOf(
                "removed: io/skjaere/usenet/plugincheck/fixture/Harbor: public final fun dock (ILjava/lang/String;)Ljava/lang/String; defaults(1,2)",
                "added: io/skjaere/usenet/plugincheck/fixture/Harbor: public final fun dock (ILjava/lang/String;)Ljava/lang/String; defaults(1)",
            ),
            ApiDump.differences(expected = dump(), actual = dropped),
        )
        // The overload kept for older plugins, hidden from new sources, is a member the dump keeps: deleting it is a removal.
        val withoutHidden = dump().replace("\tpublic final synthetic fun dock (I)Ljava/lang/String;\n", "")
        assertEquals(
            listOf("removed: io/skjaere/usenet/plugincheck/fixture/Harbor: public final synthetic fun dock (I)Ljava/lang/String;"),
            ApiDump.differences(expected = dump(), actual = withoutHidden),
        )
    }

    /** check-dump's advice when the dump differs: L614's rule, and the two additions that are a minor only as the KDoc keeps them. */
    @Test
    fun `the check's advice names the additions that are a minor only with an else branch or a body`() {
        assertTrue("addition raises plugin-api.txt's minor" in DUMP_ADVICE, DUMP_ADVICE)
        assertTrue("enum" in DUMP_ADVICE && "else branch" in DUMP_ADVICE, DUMP_ADVICE)
        assertTrue("interface a plugin implements" in DUMP_ADVICE && "a body" in DUMP_ADVICE, DUMP_ADVICE)
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
        val checkedIn = dump().replace("\tprotected fun moor ()Ljava/lang/String;\n", "\tprotected fun moor ()Ljava/lang/String;\n\tpublic final fun moorAt (I)V\n")
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
