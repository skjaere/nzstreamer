package io.skjaere.usenet.plugincheck

import io.skjaere.usenet.apicheck.ApiDatabase
import io.skjaere.usenet.apicheck.DexDumpParser
import io.skjaere.usenet.apicheck.SdkStubs
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkCheckTest {

    private val host = DexDumpParser.parse(HOST.lineSequence()) { false }
    private val plugin = DexDumpParser.parse(PLUGIN.lineSequence()) { it.startsWith("app/nzstreamer/plugin/") }
    private val api = ApiDatabase.parse(API_VERSIONS.byteInputStream())

    // android.jar's classes: Bundle has getString since API 1 and the typed getParcelable since 33; Activity's
    // getString is Context's, inherited.
    private val sdk = sdkStubs(
        stub("java/lang/Object", null, "<init>()V", "hashCode()I"),
        stub("android/os/Bundle", "java/lang/Object", "<init>()V", "getString(Ljava/lang/String;)Ljava/lang/String;", "getParcelable(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;"),
        stub("android/content/Context", "java/lang/Object", "getString(I)Ljava/lang/String;"),
        stub("android/app/Activity", "android/content/Context", "<init>()V"),
    )

    private fun violations() = LinkCheck(host, sdk, api, minSdk = 26).check("kestrel", plugin)

    @Test
    fun `a method the host's dex lacks is flagged`() {
        val flagged = violations()
        val badge = "io.skjaere.usenet.plugin.PluginTab#getBadge()Ljava/lang/String;"
        assertTrue("app.nzstreamer.plugin.kestrel.KestrelPlugin.tab: $badge isn't in NZStreamer's build" in flagged, flagged.joinToString("\n"))
    }

    @Test
    fun `a method android jar has only above minSdk 26 is flagged`() {
        val flagged = violations()
        val parcelable = "android.os.Bundle#getParcelable(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;"
        assertTrue("app.nzstreamer.plugin.kestrel.KestrelPlugin.tab: $parcelable needs API 33, above minSdk 26" in flagged, flagged.joinToString("\n"))
    }

    @Test
    fun `exactly those two are flagged`() {
        // Everything else resolves: the host's classes and members, one inherited through the host into android.jar, a
        // field, android.jar's at or below minSdk, a type, and the plugin's own classes, which aren't looked up.
        assertEquals(
            listOf(
                "app.nzstreamer.plugin.kestrel.KestrelPlugin.tab: android.os.Bundle#getParcelable(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object; " +
                    "needs API 33, above minSdk 26",
                "app.nzstreamer.plugin.kestrel.KestrelPlugin.tab: io.skjaere.usenet.plugin.PluginTab#getBadge()Ljava/lang/String; isn't in NZStreamer's build",
            ),
            violations(),
        )
    }

    @Test
    fun `a class neither the host nor android jar has is flagged`() {
        val missing = DexDumpParser.parse(PLUGIN.replace("Lio/skjaere/usenet/plugin/PluginTab;", "Lio/skjaere/usenet/plugin/PluginBadge;").lineSequence()) {
            it.startsWith("app/nzstreamer/plugin/")
        }
        val flagged = LinkCheck(host, sdk, api, minSdk = 26).check("kestrel", missing)
        val badge = "app.nzstreamer.plugin.kestrel.KestrelPlugin.tab: io.skjaere.usenet.plugin.PluginBadge isn't in NZStreamer's build"
        assertTrue(badge in flagged, flagged.joinToString("\n"))
    }

    private companion object {
        fun stub(name: String, superName: String?, vararg methods: String): Pair<String, ByteArray> =
            name to ClassWriter(0).apply {
                visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, null)
                methods.forEach { visitMethod(Opcodes.ACC_PUBLIC, it.substringBefore('('), "(" + it.substringAfter('('), null, null).visitEnd() }
                visitEnd()
            }.toByteArray()

        fun sdkStubs(vararg classes: Pair<String, ByteArray>) = SdkStubs(mapOf(*classes)::get)

        val API_VERSIONS = """
            <?xml version="1.0" encoding="utf-8"?>
            <api version="3">
                <class name="android/app/Activity" since="1">
                    <extends name="android/content/Context"/>
                    <method name="&lt;init>()V"/>
                </class>
                <class name="android/content/Context" since="1">
                    <extends name="java/lang/Object"/>
                    <method name="getString(I)Ljava/lang/String;"/>
                </class>
                <class name="android/os/Bundle" since="1">
                    <extends name="java/lang/Object"/>
                    <method name="&lt;init>()V"/>
                    <method name="getString(Ljava/lang/String;)Ljava/lang/String;"/>
                    <method name="getParcelable(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;" since="33"/>
                </class>
                <class name="java/lang/Object" since="1">
                    <method name="&lt;init>()V"/>
                    <method name="hashCode()I"/>
                </class>
            </api>
        """.trimIndent()

        // The host's dex: the API's PluginTab, and a host class over android's Activity. Only declared members matter.
        val HOST = """
            Processing 'nzstreamer.apk'...
            Opened 'classes.dex', DEX version '039'
            Class #0            -
              Class descriptor  : 'Lio/skjaere/usenet/plugin/PluginTab;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
              Static fields     -
              Instance fields   -
                #0              : (in Lio/skjaere/usenet/plugin/PluginTab;)
                  name          : 'label'
                  type          : 'Ljava/lang/String;'
                  access        : 0x0012 (PRIVATE FINAL)
              Direct methods    -
                #0              : (in Lio/skjaere/usenet/plugin/PluginTab;)
                  name          : '<init>'
                  type          : '(Ljava/lang/String;Landroidx/compose/ui/graphics/vector/ImageVector;)V'
                  access        : 0x10001 (PUBLIC CONSTRUCTOR)
              Virtual methods   -
                #0              : (in Lio/skjaere/usenet/plugin/PluginTab;)
                  name          : 'getLabel'
                  type          : '()Ljava/lang/String;'
                  access        : 0x0011 (PUBLIC FINAL)
              source_file_idx   : 201 (NzPlugin.kt)

            Class #1            -
              Class descriptor  : 'Landroidx/activity/ComponentActivity;'
              Access flags      : 0x0001 (PUBLIC)
              Superclass        : 'Landroid/app/Activity;'
              Interfaces        -
              Static fields     -
                #0              : (in Landroidx/activity/ComponentActivity;)
                  name          : 'TAG'
                  type          : 'Ljava/lang/String;'
                  access        : 0x0019 (PUBLIC STATIC FINAL)
              Instance fields   -
              Direct methods    -
              Virtual methods   -
              source_file_idx   : 202 (ComponentActivity.kt)

        """.trimIndent()

        // The plugin's dex: one class naming the host's members, android.jar's, and its own.
        val PLUGIN = """
            Processing 'kestrel.apk'...
            Opened 'classes.dex', DEX version '039'
            Class #0            -
              Class descriptor  : 'Lapp/nzstreamer/plugin/kestrel/KestrelPlugin;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
              Static fields     -
              Instance fields   -
              Direct methods    -
              Virtual methods   -
                #0              : (in Lapp/nzstreamer/plugin/kestrel/KestrelPlugin;)
                  name          : 'tab'
                  type          : '(Landroidx/activity/ComponentActivity;Landroid/os/Bundle;)Lio/skjaere/usenet/plugin/PluginTab;'
                  access        : 0x0011 (PUBLIC FINAL)
                  code          -
                  registers     : 6
                  ins           : 3
                  outs          : 3
                  insns size    : 30 16-bit code units
            00a120:                                        |[00a120] app.nzstreamer.plugin.kestrel.KestrelPlugin.tab:(Landroidx/activity/ComponentActivity;Landroid/os/Bundle;)Lio/skjaere/usenet/plugin/PluginTab;
            00a130: 2200 1000                              |0000: new-instance v0, Lio/skjaere/usenet/plugin/PluginTab; // type@0010
            00a134: 7030 3200 1002                         |0002: invoke-direct {v0, v1, v2}, Lio/skjaere/usenet/plugin/PluginTab;.<init>:(Ljava/lang/String;Landroidx/compose/ui/graphics/vector/ImageVector;)V // method@0032
            00a13a: 6e10 3300 0000                         |0005: invoke-virtual {v0}, Lio/skjaere/usenet/plugin/PluginTab;.getLabel:()Ljava/lang/String; // method@0033
            00a140: 6e10 3400 0000                         |0008: invoke-virtual {v0}, Lio/skjaere/usenet/plugin/PluginTab;.getBadge:()Ljava/lang/String; // method@0034
            00a146: 6e10 3500 0000                         |000b: invoke-virtual {v0}, Lio/skjaere/usenet/plugin/PluginTab;.hashCode:()I // method@0035
            00a14c: 6e20 3600 1400                         |000e: invoke-virtual {v4, v1}, Landroidx/activity/ComponentActivity;.getString:(I)Ljava/lang/String; // method@0036
            00a152: 6201 3700                              |0011: sget-object v1, Landroidx/activity/ComponentActivity;.TAG:Ljava/lang/String; // field@0037
            00a156: 6e20 3800 1500                         |0013: invoke-virtual {v5, v1}, Landroid/os/Bundle;.getString:(Ljava/lang/String;)Ljava/lang/String; // method@0038
            00a15c: 6e30 3900 1502                         |0016: invoke-virtual {v5, v1, v2}, Landroid/os/Bundle;.getParcelable:(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object; // method@0039
            00a162: 7010 3a00 0300                         |0019: invoke-direct {v3}, Lapp/nzstreamer/plugin/kestrel/KestrelPlugin;.helper:()V // method@003a
            00a168: 1100                                   |001c: return-object v0
                  catches       : (none)

              source_file_idx   : 101 (KestrelPlugin.kt)

        """.trimIndent()
    }
}
