package io.skjaere.usenet.apicheck

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinApiCheckerTest {

    private val api = ApiDatabase.parse(API_VERSIONS.byteInputStream())
    private val classes = DexDumpParser.parse(DEXDUMP.lineSequence()) { it.startsWith("io/skjaere/") }

    // android.jar has the hidden AbstractStringBuilder that api-versions.xml leaves out.
    private val sdk = sdkStubs(
        stub("java/lang/AbstractStringBuilder", "java/lang/Object", "setLength(I)V"),
        stub("java/lang/StringBuilder", "java/lang/AbstractStringBuilder", "<init>()V"),
        stub("java/lang/Thread", "java/lang/Object", "getId()J"),
    )

    private fun violations(allowlist: Allowlist = Allowlist.EMPTY, sdk: SdkStubs = this.sdk) =
        MinApiChecker(api, minSdk = 26, allowlist, sdk).check(classes).map { it.describe() }

    @Test
    fun `a JDK method added after minSdk is flagged`() {
        assertTrue("io.skjaere.demo.Reader.parse: java.io.InputStream#readNBytes([BII)I needs API 33" in violations())
        assertTrue("io.skjaere.demo.Reader.parse: java.io.ByteArrayOutputStream#writeBytes([B)V needs API 33" in violations())
    }

    @Test
    fun `JDK APIs at or below minSdk are not flagged`() {
        val flagged = violations().joinToString("\n")
        listOf("#read([BII)I", "java.nio.file.Files", "StandardCharsets", "UncheckedIOException", "#<init>").forEach {
            assertTrue(it !in flagged, "$it flagged:\n$flagged")
        }
    }

    @Test
    fun `a call through a JDK subclass needs the level of the class that declares the method`() {
        val flagged = violations()
        assertTrue(
            "io.skjaere.demo.Reader.parse: java.io.ByteArrayInputStream#readNBytes([BII)I (declared in java.io.InputStream) needs API 33" in flagged,
        )
        // write([BII)V is only listed on OutputStream; the subclass inherits it from API 1.
        assertTrue(flagged.none { "ByteArrayOutputStream#write(" in it })
    }

    @Test
    fun `a call through an app class resolves to the JDK class it inherits from`() {
        val flagged = violations()
        assertTrue(
            "io.skjaere.demo.User.drain: io.skjaere.demo.CountingStream#readAllBytes()[B (declared in java.io.InputStream) needs API 33" in flagged,
        )
        // Its own override and an inherited API 1 method are fine.
        assertTrue(flagged.none { "CountingStream#read(" in it || "CountingStream#skip(" in it })
    }

    @Test
    fun `classes and members Android does not have are flagged`() {
        val flagged = violations()
        assertTrue("io.skjaere.demo.Reader.parse: java.lang.ScopedValue#newInstance()Ljava/lang/ScopedValue; is not in the Android SDK" in flagged)
        assertTrue("io.skjaere.demo.Reader.parse: java.lang.Thread#threadId()J is not in the Android SDK" in flagged)
        assertTrue("io.skjaere.demo.Reader.parse: java.util.SequencedCollection needs API 35" in flagged)
        assertTrue("io.skjaere.demo.Subscriber: java.util.concurrent.Flow\$Subscriber needs API 30" in flagged)
    }

    @Test
    fun `a member api-versions does not list is only flagged when android jar lacks it too`() {
        val setLength = "io.skjaere.demo.Reader.parse: java.lang.StringBuilder#setLength(I)V is not in the Android SDK"
        assertTrue(setLength in violations(sdk = SdkStubs.NONE))
        assertTrue(setLength !in violations())
        assertTrue("io.skjaere.demo.Reader.parse: java.lang.Thread#threadId()J is not in the Android SDK" in violations())
    }

    @Test
    fun `an allowlisted use is not flagged`() {
        val entry = "io.skjaere.demo.Guarded.copy: java.io.InputStream#transferTo(Ljava/io/OutputStream;)J needs API 33"
        assertTrue(entry in violations())

        val allowlist = Allowlist.parse(
            """
            # Guarded.copy only calls transferTo behind SDK_INT >= 33.
            io.skjaere.demo.Guarded java.io.InputStream#transferTo(Ljava/io/OutputStream;)J  # see Guarded.kt
            """.trimIndent(),
        )
        assertTrue(entry !in violations(allowlist))
        assertEquals(violations().size - 1, violations(allowlist).size)

        // An entry can also name the method, as the report prints it.
        val byMethod = Allowlist.parse("io.skjaere.demo.Guarded.copy java.io.InputStream#transferTo(Ljava/io/OutputStream;)J")
        assertEquals(violations(allowlist), violations(byMethod))
        assertEquals(violations(), violations(Allowlist.parse("io.skjaere.demo.Guarded.other java.io.InputStream#transferTo(Ljava/io/OutputStream;)J")))
    }

    @Test
    fun `exactly the fixture's offending references are reported`() {
        // Leaves out android.* (lint's job), the unscanned com.example class, the array clone and
        // the call spelled out inside a string constant.
        assertEquals(
            listOf(
                "io.skjaere.demo.Guarded.copy: java.io.InputStream#transferTo(Ljava/io/OutputStream;)J needs API 33",
                "io.skjaere.demo.Reader.parse: java.io.ByteArrayInputStream#readNBytes([BII)I (declared in java.io.InputStream) needs API 33",
                "io.skjaere.demo.Reader.parse: java.io.ByteArrayOutputStream#writeBytes([B)V needs API 33",
                "io.skjaere.demo.Reader.parse: java.io.InputStream#readNBytes([BII)I needs API 33",
                "io.skjaere.demo.Reader.parse: java.io.InputStream#readNBytes([BII)I needs API 33",
                "io.skjaere.demo.Reader.parse: java.lang.ScopedValue#newInstance()Ljava/lang/ScopedValue; is not in the Android SDK",
                "io.skjaere.demo.Reader.parse: java.lang.Thread#threadId()J is not in the Android SDK",
                "io.skjaere.demo.Reader.parse: java.util.SequencedCollection needs API 35",
                "io.skjaere.demo.Subscriber: java.util.concurrent.Flow\$Subscriber needs API 30",
                "io.skjaere.demo.User.drain: io.skjaere.demo.CountingStream#readAllBytes()[B (declared in java.io.InputStream) needs API 33",
            ),
            violations().sorted(),
        )
    }

    @Test
    fun `the parser reads supertypes, declared members and code references`() {
        val stream = classes.single { it.name == "io/skjaere/demo/CountingStream" }
        assertEquals("java/io/InputStream", stream.superclass)
        assertEquals(setOf("<init>(Ljava/io/InputStream;)V", "read()I", "read([BII)I"), stream.methods)
        assertEquals(setOf("source"), stream.fields)

        val reader = classes.single { it.name == "io/skjaere/demo/Reader" }
        assertEquals(listOf("java/io/Closeable"), reader.interfaces)
        assertTrue(Reference.Method("parse", "java/io/InputStream", "readNBytes", "([BII)I") in reader.references)
        assertTrue(Reference.Field("parse", "java/nio/charset/StandardCharsets", "UTF_8") in reader.references)
        assertTrue(Reference.Type("parse", "java/io/UncheckedIOException") in reader.references)
        assertTrue(Reference.Type(null, "java/io/Closeable") in reader.references)

        // Classes outside the scanned packages only contribute their hierarchy.
        assertEquals(emptyList(), classes.single { it.name == "com/example/Other" }.references)
    }

    private companion object {
        fun stub(name: String, superName: String, vararg methods: String): Pair<String, ByteArray> =
            name to ClassWriter(0).apply {
                visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, null)
                methods.forEach { visitMethod(Opcodes.ACC_PUBLIC, it.substringBefore('('), "(" + it.substringAfter('('), null, null).visitEnd() }
                visitEnd()
            }.toByteArray()

        fun sdkStubs(vararg classes: Pair<String, ByteArray>) = SdkStubs(mapOf(*classes)::get)

        val API_VERSIONS = """
            <?xml version="1.0" encoding="utf-8"?>
            <api version="3">
                <class name="android/media/MediaCodec" since="16">
                    <extends name="java/lang/Object"/>
                    <method name="getSupportedVendorParameters()Ljava/util/List;" since="31"/>
                </class>
                <class name="java/io/ByteArrayInputStream" since="1">
                    <extends name="java/io/InputStream"/>
                    <method name="&lt;init>([B)V"/>
                </class>
                <class name="java/io/ByteArrayOutputStream" since="1">
                    <extends name="java/io/OutputStream"/>
                    <method name="&lt;init>()V"/>
                    <method name="writeBytes([B)V" since="33"/>
                </class>
                <class name="java/io/Closeable" since="1">
                    <extends name="java/lang/Object"/>
                    <implements name="java/lang/AutoCloseable" since="19"/>
                    <method name="close()V"/>
                </class>
                <class name="java/io/InputStream" since="1">
                    <extends name="java/lang/Object"/>
                    <implements name="java/io/Closeable"/>
                    <method name="&lt;init>()V"/>
                    <method name="read()I"/>
                    <method name="read([BII)I"/>
                    <method name="readAllBytes()[B" since="33"/>
                    <method name="readNBytes([BII)I" since="33"/>
                    <method name="skip(J)J"/>
                    <method name="transferTo(Ljava/io/OutputStream;)J" since="33"/>
                </class>
                <class name="java/io/OutputStream" since="1">
                    <extends name="java/lang/Object"/>
                    <implements name="java/io/Closeable"/>
                    <method name="write([BII)V"/>
                </class>
                <class name="java/io/UncheckedIOException" since="24">
                    <extends name="java/lang/RuntimeException"/>
                    <method name="&lt;init>(Ljava/lang/String;Ljava/io/IOException;)V"/>
                </class>
                <class name="java/lang/AutoCloseable" since="19">
                    <extends name="java/lang/Object"/>
                    <method name="close()V"/>
                </class>
                <class name="java/lang/StringBuilder" since="1">
                    <extends name="java/lang/Object"/>
                    <method name="&lt;init>()V"/>
                </class>
                <class name="java/lang/Object" since="1">
                    <method name="&lt;init>()V"/>
                    <method name="hashCode()I"/>
                </class>
                <class name="java/lang/Thread" since="1">
                    <extends name="java/lang/Object"/>
                    <method name="getId()J"/>
                </class>
                <class name="java/nio/charset/StandardCharsets" since="19">
                    <extends name="java/lang/Object"/>
                    <field name="UTF_8"/>
                </class>
                <class name="java/nio/file/Files" since="26">
                    <extends name="java/lang/Object"/>
                    <method name="newInputStream(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;"/>
                </class>
                <class name="java/util/SequencedCollection" since="35">
                    <extends name="java/lang/Object"/>
                </class>
                <class name="java/util/concurrent/Flow${'$'}Subscriber" since="30">
                    <extends name="java/lang/Object"/>
                </class>
            </api>
        """.trimIndent()

        // dexdump -d -n output for a few hand-written classes, in the tool's real format.
        val DEXDUMP = """
            Processing 'classes.dex'...
            Opened 'classes.dex', DEX version '039'
            Class #0            -
              Class descriptor  : 'Lio/skjaere/demo/Reader;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
                #0              : 'Ljava/io/Closeable;'
              Static fields     -
              Instance fields   -
              Direct methods    -
                #0              : (in Lio/skjaere/demo/Reader;)
                  name          : '<init>'
                  type          : '()V'
                  access        : 0x10001 (PUBLIC CONSTRUCTOR)
                  code          -
                  registers     : 1
                  ins           : 1
                  outs          : 1
                  insns size    : 4 16-bit code units
            00a100:                                        |[00a100] io.skjaere.demo.Reader.<init>:()V
            00a110: 7010 1234 0000                         |0000: invoke-direct {v0}, Ljava/lang/Object;.<init>:()V // method@1234
            00a116: 0e00                                   |0003: return-void
                  catches       : (none)

              Virtual methods   -
                #0              : (in Lio/skjaere/demo/Reader;)
                  name          : 'close'
                  type          : '()V'
                  access        : 0x0011 (PUBLIC FINAL)
                  code          -
                  registers     : 1
                  ins           : 1
                  outs          : 0
                  insns size    : 1 16-bit code units
            00a120:                                        |[00a120] io.skjaere.demo.Reader.close:()V
            00a130: 0e00                                   |0000: return-void
                  catches       : (none)

                #1              : (in Lio/skjaere/demo/Reader;)
                  name          : 'parse'
                  type          : '(Ljava/io/InputStream;)V'
                  access        : 0x0011 (PUBLIC FINAL)
                  code          -
                  registers     : 8
                  ins           : 2
                  outs          : 4
                  insns size    : 80 16-bit code units
            00a140:                                        |[00a140] io.skjaere.demo.Reader.parse:(Ljava/io/InputStream;)V
            00a150: 6e40 7da9 5078                         |0000: invoke-virtual {v7, v0, v1, v2}, Ljava/io/InputStream;.readNBytes:([BII)I // method@a97d
            00a156: 0a03                                   |0003: move-result v3
            00a158: 6e40 7ca9 5078                         |0004: invoke-virtual {v7, v0, v1, v2}, Ljava/io/InputStream;.read:([BII)I // method@a97c
            00a15e: 6e40 7da9 5078                         |0007: invoke-virtual {v7, v0, v1, v2}, Ljava/io/InputStream;.readNBytes:([BII)I // method@a97d
            00a164: 2204 1200                              |000a: new-instance v4, Ljava/io/ByteArrayInputStream; // type@0012
            00a168: 7020 1300 0400                         |000c: invoke-direct {v4, v0}, Ljava/io/ByteArrayInputStream;.<init>:([B)V // method@0013
            00a16e: 6e40 1400 1504                         |000f: invoke-virtual {v4, v0, v1, v2}, Ljava/io/ByteArrayInputStream;.readNBytes:([BII)I // method@0014
            00a174: 2205 1500                              |0012: new-instance v5, Ljava/io/ByteArrayOutputStream; // type@0015
            00a178: 7010 1600 0500                         |0014: invoke-direct {v5}, Ljava/io/ByteArrayOutputStream;.<init>:()V // method@0016
            00a17e: 6e40 1700 0510                         |0017: invoke-virtual {v5, v0, v1, v2}, Ljava/io/ByteArrayOutputStream;.write:([BII)V // method@0017
            00a184: 6e20 1800 0500                         |001a: invoke-virtual {v5, v0}, Ljava/io/ByteArrayOutputStream;.writeBytes:([B)V // method@0018
            00a18a: 7100 1900 0000                         |001d: invoke-static {}, Ljava/lang/ScopedValue;.newInstance:()Ljava/lang/ScopedValue; // method@0019
            00a190: 7120 1a00 0600                         |0020: invoke-static {v6, v0}, Ljava/nio/file/Files;.newInputStream:(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream; // method@001a
            00a196: 6e10 1b00 0600                         |0023: invoke-virtual {v6}, Ljava/lang/Thread;.threadId:()J // method@001b
            00a19c: 6e10 1c00 0600                         |0026: invoke-virtual {v6}, Ljava/lang/Thread;.getId:()J // method@001c
            00a1a2: 6206 1d00                              |0029: sget-object v6, Ljava/nio/charset/StandardCharsets;.UTF_8:Ljava/nio/charset/Charset; // field@001d
            00a1a6: 1f06 1e00                              |002b: check-cast v6, Ljava/util/SequencedCollection; // type@001e
            00a1aa: 6e10 1f00 0600                         |002d: invoke-virtual {v6}, Landroid/media/MediaCodec;.getSupportedVendorParameters:()Ljava/util/List; // method@001f
            00a1b0: 6e10 2000 0000                         |0030: invoke-virtual {v0}, [B.clone:()Ljava/lang/Object; // method@0020
            00a1b6: 1a06 2100                              |0033: const-string v6, "|0000: invoke-virtual {v0}, Ljava/lang/Thread;.ofVirtual:()V" // string@0021
            00a1ba: 2306 2200                              |0035: new-array v6, v3, [Ljava/io/UncheckedIOException; // type@0022
            00a1be: 6e20 2300 3600                         |0037: invoke-virtual {v6, v3}, Ljava/lang/StringBuilder;.setLength:(I)V // method@0023
            00a1c4: 0e00                                   |003a: return-void
            00a1c6: 0d06                                   |003b: move-exception v6
            00a1c8: 2706                                   |003c: throw v6
                  catches       : 1
                    0x0000 - 0x003a
                      Ljava/io/UncheckedIOException; -> 0x003b
                      <any> -> 0x003b

              source_file_idx   : 101 (Reader.kt)

            Class #1            -
              Class descriptor  : 'Lio/skjaere/demo/CountingStream;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/io/InputStream;'
              Interfaces        -
              Static fields     -
              Instance fields   -
                #0              : (in Lio/skjaere/demo/CountingStream;)
                  name          : 'source'
                  type          : 'Ljava/io/InputStream;'
                  access        : 0x0012 (PRIVATE FINAL)
              Direct methods    -
                #0              : (in Lio/skjaere/demo/CountingStream;)
                  name          : '<init>'
                  type          : '(Ljava/io/InputStream;)V'
                  access        : 0x10001 (PUBLIC CONSTRUCTOR)
                  code          -
                  registers     : 2
                  ins           : 2
                  outs          : 1
                  insns size    : 6 16-bit code units
            00b100:                                        |[00b100] io.skjaere.demo.CountingStream.<init>:(Ljava/io/InputStream;)V
            00b110: 7010 3000 0000                         |0000: invoke-direct {v0}, Ljava/io/InputStream;.<init>:()V // method@0030
            00b116: 5b01 3100                              |0003: iput-object v1, v0, Lio/skjaere/demo/CountingStream;.source:Ljava/io/InputStream; // field@0031
            00b11a: 0e00                                   |0005: return-void
                  catches       : (none)

              Virtual methods   -
                #0              : (in Lio/skjaere/demo/CountingStream;)
                  name          : 'read'
                  type          : '()I'
                  access        : 0x0001 (PUBLIC)
                  code          -
                  registers     : 2
                  ins           : 1
                  outs          : 1
                  insns size    : 6 16-bit code units
            00b120:                                        |[00b120] io.skjaere.demo.CountingStream.read:()I
            00b130: 5410 3100                              |0000: iget-object v0, v1, Lio/skjaere/demo/CountingStream;.source:Ljava/io/InputStream; // field@0031
            00b134: 6e10 3200 0000                         |0002: invoke-virtual {v0}, Ljava/io/InputStream;.read:()I // method@0032
            00b13a: 0a00                                   |0005: move-result v0
            00b13c: 0f00                                   |0006: return v0
                  catches       : (none)

                #1              : (in Lio/skjaere/demo/CountingStream;)
                  name          : 'read'
                  type          : '([BII)I'
                  access        : 0x0001 (PUBLIC)
                  code          -
                  registers     : 5
                  ins           : 4
                  outs          : 4
                  insns size    : 6 16-bit code units
            00b140:                                        |[00b140] io.skjaere.demo.CountingStream.read:([BII)I
            00b150: 5410 3100                              |0000: iget-object v0, v1, Lio/skjaere/demo/CountingStream;.source:Ljava/io/InputStream; // field@0031
            00b154: 6e40 7ca9 2043                         |0002: invoke-virtual {v0, v2, v3, v4}, Ljava/io/InputStream;.read:([BII)I // method@a97c
            00b15a: 0a00                                   |0005: move-result v0
            00b15c: 0f00                                   |0006: return v0
                  catches       : (none)

              source_file_idx   : 102 (CountingStream.kt)

            Class #2            -
              Class descriptor  : 'Lio/skjaere/demo/User;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
              Static fields     -
              Instance fields   -
              Direct methods    -
              Virtual methods   -
                #0              : (in Lio/skjaere/demo/User;)
                  name          : 'drain'
                  type          : '(Lio/skjaere/demo/CountingStream;)[B'
                  access        : 0x0011 (PUBLIC FINAL)
                  code          -
                  registers     : 5
                  ins           : 2
                  outs          : 4
                  insns size    : 12 16-bit code units
            00c100:                                        |[00c100] io.skjaere.demo.User.drain:(Lio/skjaere/demo/CountingStream;)[B
            00c110: 6e40 4000 1032                         |0000: invoke-virtual {v4, v0, v1, v2}, Lio/skjaere/demo/CountingStream;.read:([BII)I // method@0040
            00c116: 6e30 4100 1400                         |0003: invoke-virtual {v4, v0, v1}, Lio/skjaere/demo/CountingStream;.skip:(J)J // method@0041
            00c11c: 6e10 4200 0400                         |0006: invoke-virtual {v4}, Lio/skjaere/demo/CountingStream;.readAllBytes:()[B // method@0042
            00c122: 0c00                                   |0009: move-result-object v0
            00c124: 1100                                   |000a: return-object v0
                  catches       : (none)

              source_file_idx   : 103 (User.kt)

            Class #3            -
              Class descriptor  : 'Lio/skjaere/demo/Guarded;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
              Static fields     -
              Instance fields   -
              Direct methods    -
                #0              : (in Lio/skjaere/demo/Guarded;)
                  name          : 'copy'
                  type          : '(Ljava/io/InputStream;Ljava/io/OutputStream;)J'
                  access        : 0x0019 (PUBLIC STATIC FINAL)
                  code          -
                  registers     : 3
                  ins           : 2
                  outs          : 2
                  insns size    : 4 16-bit code units
            00d100:                                        |[00d100] io.skjaere.demo.Guarded.copy:(Ljava/io/InputStream;Ljava/io/OutputStream;)J
            00d110: 6e20 5000 2100                         |0000: invoke-virtual {v1, v2}, Ljava/io/InputStream;.transferTo:(Ljava/io/OutputStream;)J // method@0050
            00d116: 0b00                                   |0003: move-result-wide v0
            00d118: 1000                                   |0004: return-wide v0
                  catches       : (none)

              Virtual methods   -
              source_file_idx   : 104 (Guarded.kt)

            Class #4            -
              Class descriptor  : 'Lio/skjaere/demo/Subscriber;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
                #0              : 'Ljava/util/concurrent/Flow${'$'}Subscriber;'
              Static fields     -
              Instance fields   -
              Direct methods    -
              Virtual methods   -
              source_file_idx   : 105 (Subscriber.kt)

            Class #5            -
              Class descriptor  : 'Lcom/example/Other;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/util/concurrent/Flow${'$'}Subscriber;'
              Interfaces        -
              Static fields     -
              Instance fields   -
              Direct methods    -
                #0              : (in Lcom/example/Other;)
                  name          : 'read'
                  type          : '(Ljava/io/InputStream;[B)I'
                  access        : 0x0019 (PUBLIC STATIC FINAL)
                  code          -
                  registers     : 4
                  ins           : 2
                  outs          : 4
                  insns size    : 4 16-bit code units
            00e100:                                        |[00e100] com.example.Other.read:(Ljava/io/InputStream;[B)I
            00e110: 6e40 7da9 5078                         |0000: invoke-virtual {v2, v3, v0, v1}, Ljava/io/InputStream;.readNBytes:([BII)I // method@a97d
            00e116: 0a00                                   |0003: move-result v0
            00e118: 0f00                                   |0004: return v0
                  catches       : (none)

              Virtual methods   -
              source_file_idx   : 106 (Other.kt)

        """.trimIndent()
    }
}
