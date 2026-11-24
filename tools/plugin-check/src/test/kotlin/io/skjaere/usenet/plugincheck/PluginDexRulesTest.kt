package io.skjaere.usenet.plugincheck

import io.skjaere.usenet.apicheck.DexDumpParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginDexRulesTest {

    // The plugin API's and the UI kit's classes, as their class jars list them.
    private val shared = SharedClasses(
        setOf("io/skjaere/usenet/plugin/NzPlugin", "io/skjaere/usenet/plugin/PluginTab", "io/skjaere/usenet/app/ui/ComponentsKt"),
    )
    private val rules = PluginDexRules("kestrel", shared)
    private val classes = DexDumpParser.parse(DEXDUMP.lineSequence()) { true }

    private fun violations(entries: List<String> = CLEAN_ENTRIES) = rules.check(classes, entries)

    // A violation as reported, with or without its count of references.
    private infix fun List<String>.has(text: String) = any { it == text || it.startsWith("$text (") }

    @Test
    fun `a class outside the plugin's package is flagged`() {
        assertTrue(violations() has "carries com.example.Other, outside app.nzstreamer.plugin.kestrel")
    }

    @Test
    fun `a shared Compose class left in the dex is flagged`() {
        assertTrue(
            violations() has "carries androidx.compose.runtime.ComposerKt, which NZStreamer gives every plugin: take its library " +
                "compileOnly",
        )
    }

    @Test
    fun `android util Log is flagged`() {
        assertTrue(
            violations() has "app.nzstreamer.plugin.kestrel.Pages.draw: android.util.Log#i(Ljava/lang/String;Ljava/lang/String;)I, " +
                "whose lines reach logcat unredacted: log through slf4j",
        )
    }

    @Test
    fun `System out, System err and printStackTrace are flagged`() {
        val flagged = violations()
        val draw = "app.nzstreamer.plugin.kestrel.Pages.draw"
        assertTrue(flagged has "$draw: java.lang.System#out, which reaches logcat unredacted: log through slf4j")
        assertTrue(flagged has "$draw: java.lang.System#err, which reaches logcat unredacted: log through slf4j")
        assertTrue(
            flagged has "$draw: java.lang.IllegalStateException#printStackTrace()V, which reaches logcat unredacted: log through slf4j",
        )
    }

    @Test
    fun `a class that declares Parcelable, and one that declares Serializable, are flagged`() {
        val flagged = violations()
        assertTrue(
            flagged has "app.nzstreamer.plugin.kestrel.Saved declares android.os.Parcelable: after a process death the host can't " +
                "make a plugin's types again",
        )
        assertTrue(
            flagged has "app.nzstreamer.plugin.kestrel.Kept declares java.io.Serializable: after a process death the host can't " +
                "make a plugin's types again",
        )
    }

    @Test
    fun `a suspend lambda and an enum pass, though their supertypes are Serializable`() {
        val flagged = violations().joinToString("\n")
        assertTrue("Pages\$load\$1" !in flagged, flagged)
        assertTrue("kestrel.Side" !in flagged, flagged)
    }

    @Test
    fun `an R class with resource ids, and a reference to an R class, are flagged, not the empty R every app build makes`() {
        val flagged = violations()
        assertTrue(flagged has "carries app.nzstreamer.plugin.kestrel.R\$drawable, an R class: plugins have no resources")
        assertTrue(flagged.none { it.startsWith("carries app.nzstreamer.plugin.kestrel.R,") }, flagged.joinToString("\n"))
        assertTrue(
            flagged has "app.nzstreamer.plugin.kestrel.Pages.draw: app.nzstreamer.plugin.kestrel.R\$drawable, an R class: plugins use no resources",
        )
        assertTrue(
            flagged has "app.nzstreamer.plugin.kestrel.Pages.draw: io.skjaere.usenet.ui.R\$font, an R class: plugins use no resources",
        )
    }

    @Test
    fun `a lib entry and a res entry are flagged`() {
        val flagged = violations(CLEAN_ENTRIES + listOf("lib/x86_64/libkestrel.so", "res/raw/cup.png"))
        assertTrue(flagged has "lib/x86_64/libkestrel.so: plugins carry no native library")
        assertTrue(flagged has "res/raw/cup.png: plugins carry no resources")
    }

    @Test
    fun `NZStreamer's own classes outside the API and the kit are flagged, and other libraries`() {
        val flagged = violations()
        assertTrue(
            flagged has "app.nzstreamer.plugin.kestrel.Pages.draw: io.skjaere.usenet.app.AppContainer, NZStreamer's own outside the " +
                "plugin API and the UI kit",
        )
        assertTrue(flagged has "app.nzstreamer.plugin.kestrel.Pages.draw: okhttp3.OkHttpClient, which NZStreamer doesn't share with plugins")
    }

    @Test
    fun `exactly the fixture's offences are reported, each once with its count`() {
        // The clean plugin class and its references to the API, the kit, Compose, Kotlin, android and java pass.
        assertEquals(
            listOf(
                "app.nzstreamer.plugin.kestrel.Kept declares java.io.Serializable: after a process death the host can't make a " +
                    "plugin's types again",
                "app.nzstreamer.plugin.kestrel.Pages.draw: android.util.Log#i(Ljava/lang/String;Ljava/lang/String;)I, whose lines " +
                    "reach logcat unredacted: log through slf4j (2 references)",
                "app.nzstreamer.plugin.kestrel.Pages.draw: app.nzstreamer.plugin.kestrel.R\$drawable, an R class: plugins use no resources",
                "app.nzstreamer.plugin.kestrel.Pages.draw: io.skjaere.usenet.app.AppContainer, NZStreamer's own outside the plugin " +
                    "API and the UI kit",
                "app.nzstreamer.plugin.kestrel.Pages.draw: io.skjaere.usenet.ui.R\$font, an R class: plugins use no resources",
                "app.nzstreamer.plugin.kestrel.Pages.draw: java.lang.IllegalStateException#printStackTrace()V, which reaches logcat " +
                    "unredacted: log through slf4j",
                "app.nzstreamer.plugin.kestrel.Pages.draw: java.lang.System#err, which reaches logcat unredacted: log through slf4j",
                "app.nzstreamer.plugin.kestrel.Pages.draw: java.lang.System#out, which reaches logcat unredacted: log through slf4j",
                "app.nzstreamer.plugin.kestrel.Pages.draw: okhttp3.OkHttpClient, which NZStreamer doesn't share with plugins " +
                    "(2 references)",
                "app.nzstreamer.plugin.kestrel.Saved declares android.os.Parcelable: after a process death the host can't make a " +
                    "plugin's types again",
                "carries androidx.compose.runtime.ComposerKt, which NZStreamer gives every plugin: take its library compileOnly",
                "carries app.nzstreamer.plugin.kestrel.R\$drawable, an R class: plugins have no resources",
                "carries com.example.Other, outside app.nzstreamer.plugin.kestrel",
            ),
            violations(),
        )
    }

    @Test
    fun `a clean plugin passes`() {
        val clean = classes.filter { it.name == "app/nzstreamer/plugin/kestrel/KestrelPlugin" }
        assertEquals(emptyList(), rules.check(clean, CLEAN_ENTRIES))
    }

    private companion object {
        val CLEAN_ENTRIES = listOf("AndroidManifest.xml", "classes.dex", "resources.arsc", "META-INF/kestrel_debug.kotlin_module")

        // dexdump -d -n output for a few hand-written classes, in the tool's real format.
        val DEXDUMP = """
            Processing 'kestrel.apk'...
            Opened 'kestrel.apk', DEX version '039'
            Class #0            -
              Class descriptor  : 'Lapp/nzstreamer/plugin/kestrel/KestrelPlugin;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
                #0              : 'Lio/skjaere/usenet/plugin/NzPlugin;'
              Static fields     -
              Instance fields   -
              Direct methods    -
                #0              : (in Lapp/nzstreamer/plugin/kestrel/KestrelPlugin;)
                  name          : '<init>'
                  type          : '()V'
                  access        : 0x10001 (PUBLIC CONSTRUCTOR)
                  code          -
                  registers     : 1
                  ins           : 1
                  outs          : 1
                  insns size    : 4 16-bit code units
            00a100:                                        |[00a100] app.nzstreamer.plugin.kestrel.KestrelPlugin.<init>:()V
            00a110: 7010 1234 0000                         |0000: invoke-direct {v0}, Ljava/lang/Object;.<init>:()V // method@1234
            00a116: 0e00                                   |0003: return-void
                  catches       : (none)

              Virtual methods   -
                #0              : (in Lapp/nzstreamer/plugin/kestrel/KestrelPlugin;)
                  name          : 'tab'
                  type          : '()Lio/skjaere/usenet/plugin/PluginTab;'
                  access        : 0x0011 (PUBLIC FINAL)
                  code          -
                  registers     : 4
                  ins           : 1
                  outs          : 3
                  insns size    : 16 16-bit code units
            00a120:                                        |[00a120] app.nzstreamer.plugin.kestrel.KestrelPlugin.tab:()Lio/skjaere/usenet/plugin/PluginTab;
            00a130: 2200 1000                              |0000: new-instance v0, Lio/skjaere/usenet/plugin/PluginTab; // type@0010
            00a134: 1a01 2000                              |0002: const-string v1, "Kestrel" // string@0020
            00a138: 6202 3000                              |0004: sget-object v2, Landroidx/compose/material/icons/Icons${'$'}Filled;.INSTANCE:Landroidx/compose/material/icons/Icons${'$'}Filled; // field@0030
            00a13c: 7120 3100 2000                         |0006: invoke-static {v2}, Landroidx/compose/material/icons/filled/StarKt;.getStar:(Landroidx/compose/material/icons/Icons${'$'}Filled;)Landroidx/compose/ui/graphics/vector/ImageVector; // method@0031
            00a142: 0c02                                   |0009: move-result-object v2
            00a144: 7030 3200 1002                         |000a: invoke-direct {v0, v1, v2}, Lio/skjaere/usenet/plugin/PluginTab;.<init>:(Ljava/lang/String;Landroidx/compose/ui/graphics/vector/ImageVector;)V // method@0032
            00a14a: 7110 3300 0100                         |000d: invoke-static {v1}, Lkotlin/jvm/internal/Intrinsics;.checkNotNull:(Ljava/lang/Object;)V // method@0033
            00a150: 1100                                   |000f: return-object v0
                  catches       : (none)

                #1              : (in Lapp/nzstreamer/plugin/kestrel/KestrelPlugin;)
                  name          : 'Page'
                  type          : '(Ljava/lang/String;Ljava/lang/String;Lio/skjaere/usenet/plugin/PluginHost;Lio/skjaere/usenet/plugin/PluginNav;Landroidx/compose/runtime/Composer;I)V'
                  access        : 0x0011 (PUBLIC FINAL)
                  code          -
                  registers     : 8
                  ins           : 7
                  outs          : 4
                  insns size    : 12 16-bit code units
            00a160:                                        |[00a160] app.nzstreamer.plugin.kestrel.KestrelPlugin.Page:(Ljava/lang/String;Ljava/lang/String;Lio/skjaere/usenet/plugin/PluginHost;Lio/skjaere/usenet/plugin/PluginNav;Landroidx/compose/runtime/Composer;I)V
            00a170: 7230 4000 5106                         |0000: invoke-interface {v5, v1}, Landroidx/compose/runtime/Composer;.startRestartGroup:(I)Landroidx/compose/runtime/Composer; // method@0040
            00a176: 7140 4100 1243                         |0003: invoke-static {v1, v2, v3, v4}, Lio/skjaere/usenet/app/ui/ComponentsKt;.TopBar:(Ljava/lang/String;Lkotlin/jvm/functions/Function0;Ljava/lang/String;ZLkotlin/jvm/functions/Function3;Landroidx/compose/runtime/Composer;II)V // method@0041
            00a17c: 6e20 4200 1500                         |0006: invoke-virtual {v5, v1}, Landroid/content/Context;.getString:(I)Ljava/lang/String; // method@0042
            00a182: 7110 4300 0500                         |0009: invoke-static {v5}, Ljava/util/Objects;.requireNonNull:(Ljava/lang/Object;)Ljava/lang/Object; // method@0043
            00a188: 0e00                                   |000b: return-void
                  catches       : (none)

              source_file_idx   : 101 (KestrelPlugin.kt)

            Class #1            -
              Class descriptor  : 'Lapp/nzstreamer/plugin/kestrel/Pages;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
              Static fields     -
              Instance fields   -
              Direct methods    -
                #0              : (in Lapp/nzstreamer/plugin/kestrel/Pages;)
                  name          : 'draw'
                  type          : '(Ljava/lang/Throwable;)V'
                  access        : 0x0019 (PUBLIC STATIC FINAL)
                  code          -
                  registers     : 6
                  ins           : 1
                  outs          : 3
                  insns size    : 40 16-bit code units
            00b100:                                        |[00b100] app.nzstreamer.plugin.kestrel.Pages.draw:(Ljava/lang/Throwable;)V
            00b110: 1a00 5000                              |0000: const-string v0, "Kestrel" // string@0050
            00b114: 7120 5100 0000                         |0002: invoke-static {v0, v0}, Landroid/util/Log;.i:(Ljava/lang/String;Ljava/lang/String;)I // method@0051
            00b11a: 7120 5100 0000                         |0005: invoke-static {v0, v0}, Landroid/util/Log;.i:(Ljava/lang/String;Ljava/lang/String;)I // method@0051
            00b120: 6201 5200                              |0008: sget-object v1, Ljava/lang/System;.out:Ljava/io/PrintStream; // field@0052
            00b124: 6201 5300                              |000a: sget-object v1, Ljava/lang/System;.err:Ljava/io/PrintStream; // field@0053
            00b128: 1f05 5400                              |000c: check-cast v5, Ljava/lang/IllegalStateException; // type@0054
            00b12c: 6e10 5500 0500                         |000e: invoke-virtual {v5}, Ljava/lang/IllegalStateException;.printStackTrace:()V // method@0055
            00b132: 2202 5600                              |0011: new-instance v2, Lio/skjaere/usenet/app/AppContainer; // type@0056
            00b136: 2203 5700                              |0013: new-instance v3, Lokhttp3/OkHttpClient; // type@0057
            00b13a: 7010 5800 0300                         |0015: invoke-direct {v3}, Lokhttp3/OkHttpClient;.<init>:()V // method@0058
            00b140: 6004 5900                              |0018: sget v4, Lio/skjaere/usenet/ui/R${'$'}font;.sora:I // field@0059
            00b142: 6004 5b00                              |0019: sget v4, Lapp/nzstreamer/plugin/kestrel/R${'$'}drawable;.cup:I // field@005b
            00b144: 6e20 5a00 1500                         |001a: invoke-virtual {v5, v1}, Ljava/io/PrintStream;.println:(Ljava/lang/Object;)V // method@005a
            00b14a: 0e00                                   |001d: return-void
                  catches       : (none)

              Virtual methods   -
              source_file_idx   : 102 (Pages.kt)

            Class #2            -
              Class descriptor  : 'Lapp/nzstreamer/plugin/kestrel/Pages${'$'}load${'$'}1;'
              Access flags      : 0x0010 (FINAL)
              Superclass        : 'Lkotlin/coroutines/jvm/internal/SuspendLambda;'
              Interfaces        -
                #0              : 'Lkotlin/jvm/functions/Function2;'
              Static fields     -
              Instance fields   -
                #0              : (in Lapp/nzstreamer/plugin/kestrel/Pages${'$'}load${'$'}1;)
                  name          : 'label'
                  type          : 'I'
                  access        : 0x0000 ()
              Direct methods    -
              Virtual methods   -
              source_file_idx   : 102 (Pages.kt)

            Class #3            -
              Class descriptor  : 'Lapp/nzstreamer/plugin/kestrel/Side;'
              Access flags      : 0x4011 (PUBLIC FINAL ENUM)
              Superclass        : 'Ljava/lang/Enum;'
              Interfaces        -
              Static fields     -
              Instance fields   -
              Direct methods    -
              Virtual methods   -
              source_file_idx   : 103 (Side.kt)

            Class #4            -
              Class descriptor  : 'Lapp/nzstreamer/plugin/kestrel/Saved;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
                #0              : 'Landroid/os/Parcelable;'
              Static fields     -
              Instance fields   -
              Direct methods    -
              Virtual methods   -
              source_file_idx   : 104 (Saved.kt)

            Class #5            -
              Class descriptor  : 'Lapp/nzstreamer/plugin/kestrel/Kept;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
                #0              : 'Ljava/io/Serializable;'
              Static fields     -
              Instance fields   -
              Direct methods    -
              Virtual methods   -
              source_file_idx   : 105 (Kept.kt)

            Class #6            -
              Class descriptor  : 'Lapp/nzstreamer/plugin/kestrel/R;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
              Static fields     -
              Instance fields   -
              Direct methods    -
                #0              : (in Lapp/nzstreamer/plugin/kestrel/R;)
                  name          : '<init>'
                  type          : '()V'
                  access        : 0x10002 (PRIVATE CONSTRUCTOR)
                  code          -
                  registers     : 1
                  ins           : 1
                  outs          : 1
                  insns size    : 4 16-bit code units
            0000d0:                                        |[0000d0] app.nzstreamer.plugin.kestrel.R.<init>:()V
            0000e0: 7010 0100 0000                         |0000: invoke-direct {v0}, Ljava/lang/Object;.<init>:()V // method@0001
            0000e6: 0e00                                   |0003: return-void
                  catches       : (none)

              Virtual methods   -
              source_file_idx   : -1 (unknown)

            Class #9            -
              Class descriptor  : 'Lapp/nzstreamer/plugin/kestrel/R${'$'}drawable;'
              Access flags      : 0x0019 (PUBLIC STATIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
              Static fields     -
                #0              : (in Lapp/nzstreamer/plugin/kestrel/R${'$'}drawable;)
                  name          : 'cup'
                  type          : 'I'
                  access        : 0x0019 (PUBLIC STATIC FINAL)
              Instance fields   -
              Direct methods    -
              Virtual methods   -
              source_file_idx   : -1 (unknown)

            Class #7            -
              Class descriptor  : 'Landroidx/compose/runtime/ComposerKt;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
              Static fields     -
              Instance fields   -
              Direct methods    -
              Virtual methods   -
              source_file_idx   : 107 (Composer.kt)

            Class #8            -
              Class descriptor  : 'Lcom/example/Other;'
              Access flags      : 0x0011 (PUBLIC FINAL)
              Superclass        : 'Ljava/lang/Object;'
              Interfaces        -
              Static fields     -
              Instance fields   -
              Direct methods    -
              Virtual methods   -
              source_file_idx   : 108 (Other.kt)

        """.trimIndent()
    }
}
