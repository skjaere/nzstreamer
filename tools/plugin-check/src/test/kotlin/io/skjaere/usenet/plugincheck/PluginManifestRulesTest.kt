package io.skjaere.usenet.plugincheck

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PluginManifestRulesTest {

    private val rules = PluginManifestRules(id = "kestrel", versionCode = 3, apiMajor = 1, apiMinor = 0, libs = LIBS)

    private fun violations(badging: String = BADGING, xmltree: String = XMLTREE, resources: String = RESOURCES) =
        rules.check(badging, xmltree, resources)

    // The fixture with one meta-data's value line replaced, in both dumps: aapt2's xmltree shows the stored value, badging
    // its raw data.
    private fun withMetaData(name: String, xmltreeValue: String, badgingValue: String) = violations(
        badging = BADGING.replace(Regex("""meta-data: name='${Regex.escape(name)}' value='[^']*'"""), "meta-data: name='$name' value='$badgingValue'"),
        xmltree = XMLTREE.replace(
            Regex("""(:name\(0x01010003\)="${Regex.escape(name)}" \(Raw: "${Regex.escape(name)}"\)\n\s+A: http://schemas.android.com/apk/res/android:value\(0x01010024\)=).*"""),
            "$1${Regex.escapeReplacement(xmltreeValue)}",
        ),
    )

    @Test
    fun `a plugin as the convention builds it passes`() {
        assertEquals(emptyList(), violations())
    }

    @Test
    fun `its package is app nzstreamer plugin and its id`() {
        val other = BADGING.replace("name='app.nzstreamer.plugin.kestrel'", "name='app.nzstreamer.plugin.harbor'")
        assertEquals(listOf("its package is app.nzstreamer.plugin.harbor, not app.nzstreamer.plugin.kestrel"), violations(badging = other))
    }

    @Test
    fun `its versionCode is the one expected`() {
        assertEquals(listOf("its versionCode is 2, not 3"), violations(badging = BADGING.replace("versionCode='3'", "versionCode='2'")))
    }

    @Test
    fun `its minSdk is 26`() {
        val higher = XMLTREE.replace("minSdkVersion(0x0101020c)=26", "minSdkVersion(0x0101020c)=28")
        assertEquals(listOf("its minSdk is 28, not 26"), violations(xmltree = higher))
    }

    @Test
    fun `it declares no activity, service, receiver, provider or permission`() {
        val name = "A: http://schemas.android.com/apk/res/android:name(0x01010003)="
        // Under the application, after its meta-data; the permission beside the application, under the manifest.
        val components = listOf("activity", "service", "receiver", "provider").joinToString("") { element ->
            "          E: $element (line=20)\n            $name\".Kestrel\" (Raw: \".Kestrel\")\n"
        }
        val permission = "      E: uses-permission (line=4)\n        $name\"android.permission.INTERNET\" (Raw: \"android.permission.INTERNET\")\n"
        val declaring = XMLTREE.replace("      E: application (line=5)\n", permission + "      E: application (line=5)\n") + components
        assertEquals(
            listOf(
                "it declares a component (activity): plugins declare none",
                "it declares a component (provider): plugins declare none",
                "it declares a component (receiver): plugins declare none",
                "it declares a component (service): plugins declare none",
                "it declares a permission (uses-permission): plugins declare none",
            ),
            violations(xmltree = declaring),
        )
    }

    @Test
    fun `each of the five meta-data is there once`() {
        val missing = XMLTREE.replace("\"nzs.plugin.libs\" (Raw: \"nzs.plugin.libs\")", "\"nzs.plugin.other\" (Raw: \"nzs.plugin.other\")")
        assertEquals(listOf("it has no nzs.plugin.libs meta-data"), violations(xmltree = missing))
        val twice = XMLTREE.replace("\"nzs.plugin.libs\" (Raw: \"nzs.plugin.libs\")", "\"nzs.plugin.entry\" (Raw: \"nzs.plugin.entry\")")
        assertEquals(listOf("it has 2 nzs.plugin.entry meta-data, not one", "it has no nzs.plugin.libs meta-data"), violations(xmltree = twice))
    }

    @Test
    fun `an id stored as a boolean is refused, and another id`() {
        assertEquals(listOf("its nzs.plugin.id is stored as a boolean, not a string"), withMetaData("nzs.plugin.id", "true", "-1"))
        assertEquals(
            listOf("its nzs.plugin.id is harbor, not kestrel"),
            withMetaData("nzs.plugin.id", "\"harbor\" (Raw: \"harbor\")", "harbor"),
        )
    }

    @Test
    fun `its entry is a class under its package`() {
        assertEquals(
            listOf("its nzs.plugin.entry, app.nzstreamer.plugin.harbor.Entry, isn't a class under app.nzstreamer.plugin.kestrel"),
            withMetaData("nzs.plugin.entry", "\"app.nzstreamer.plugin.harbor.Entry\" (Raw: \"app.nzstreamer.plugin.harbor.Entry\")", "x"),
        )
        assertEquals(
            listOf("its nzs.plugin.entry, app.nzstreamer.plugin.kestrel., isn't a class under app.nzstreamer.plugin.kestrel"),
            withMetaData("nzs.plugin.entry", "\"app.nzstreamer.plugin.kestrel.\" (Raw: \"app.nzstreamer.plugin.kestrel.\")", "x"),
        )
    }

    @Test
    fun `an API stamp stored as a float, or as the string 1 point 0, is refused`() {
        // aapt2 stores "1.0" as the float 1.0: xmltree prints it as 1, badging gives its raw bits.
        assertEquals(
            listOf("its nzs.plugin.api.major is stored as a float, not an integer"),
            withMetaData("nzs.plugin.api.major", "1", "1065353216"),
        )
        assertEquals(
            listOf("its nzs.plugin.api.minor is stored as a float, not an integer"),
            withMetaData("nzs.plugin.api.minor", "1.5", "1069547520"),
        )
        assertEquals(
            listOf("its nzs.plugin.api.major is stored as a string, not an integer"),
            withMetaData("nzs.plugin.api.major", "\"1.0\" (Raw: \"1.0\")", "1.0"),
        )
    }

    @Test
    fun `its API stamp is the one expected`() {
        assertEquals(listOf("its API stamp is 1.1, not 1.0"), withMetaData("nzs.plugin.api.minor", "1", "1"))
        assertEquals(listOf("its API stamp is 2.0, not 1.0"), withMetaData("nzs.plugin.api.major", "2", "2"))
    }

    @Test
    fun `its libraries are this build's, in a string`() {
        assertEquals(
            listOf("its nzs.plugin.libs is kotlin-stdlib 2.3.21, not this build's $LIBS"),
            withMetaData("nzs.plugin.libs", "\"kotlin-stdlib 2.3.21\" (Raw: \"kotlin-stdlib 2.3.21\")", "x"),
        )
        assertEquals(listOf("its nzs.plugin.libs is stored as an integer, not a string"), withMetaData("nzs.plugin.libs", "7", "7"))
    }

    /** Written by hand, or left from an older build: the host would compare the libraries with versions it wasn't built with. */
    @Test
    fun `libraries well formed but not this build's are refused`() {
        val older = "kotlin-stdlib=2.3.21,kotlinx-coroutines=1.11.0,compose-bom=2026.01.01"
        assertEquals(
            listOf("its nzs.plugin.libs is $older, not this build's $LIBS"),
            withMetaData("nzs.plugin.libs", "\"$older\" (Raw: \"$older\")", older),
        )
    }

    @Test
    fun `the entry is read from the manifest as the host reads it`() {
        assertEquals("app.nzstreamer.plugin.kestrel.KestrelPlugin", PluginManifestRules.entry(XMLTREE))
        assertEquals(null, PluginManifestRules.entry(XMLTREE.replace("\"nzs.plugin.entry\" (Raw: \"nzs.plugin.entry\")", "\"nzs.plugin.other\" (Raw: \"nzs.plugin.other\")")))
    }

    @Test
    fun `a resources dump that lists a resource is refused`() {
        val one = """
            Binary APK
            DynamicRefTable entryCount=1
              0x7e -> app.nzstreamer.plugin.kestrel
            Package name=app.nzstreamer.plugin.kestrel id=7e
              type string id=01 entryCount=1
                resource 0x7e010000 string/greeting
                  () "Copper Crown"
        """.trimIndent()
        assertEquals(listOf("it carries a resource (string/greeting): plugins carry none"), violations(resources = one))
    }

    private companion object {
        // The libraries the build stamps, as the fixture's manifest has them.
        const val LIBS = "kotlin-stdlib=2.3.21,kotlinx-coroutines=1.11.0,compose-bom=2026.06.01"

        // aapt2 dump badging --include-meta-data, as build-tools 35 prints it.
        val BADGING = """
            package: name='app.nzstreamer.plugin.kestrel' versionCode='3' versionName='1.2.0' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'
            minSdkVersion:'26'
            targetSdkVersion:'36'
            application: label='' icon=''
            application-debuggable
            meta-data: name='nzs.plugin.id' value='kestrel'
            meta-data: name='nzs.plugin.entry' value='app.nzstreamer.plugin.kestrel.KestrelPlugin'
            meta-data: name='nzs.plugin.api.major' value='1'
            meta-data: name='nzs.plugin.api.minor' value='0'
            meta-data: name='nzs.plugin.libs' value='kotlin-stdlib=2.3.21,kotlinx-coroutines=1.11.0,compose-bom=2026.06.01'
            feature-group: label=''
              uses-feature: name='android.hardware.faketouch'
              uses-implied-feature: name='android.hardware.faketouch' reason='default feature for all apps'
            supports-screens: 'small' 'normal' 'large' 'xlarge'
            supports-any-density: 'true'
            locales:
            densities:
        """.trimIndent()

        // aapt2 dump xmltree --file AndroidManifest.xml, as build-tools 35 prints it.
        val XMLTREE = """
            N: android=http://schemas.android.com/apk/res/android (line=2)
              E: manifest (line=2)
                A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=3
                A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="1.2.0" (Raw: "1.2.0")
                A: http://schemas.android.com/apk/res/android:compileSdkVersion(0x01010572)=36
                A: http://schemas.android.com/apk/res/android:compileSdkVersionCodename(0x01010573)="16" (Raw: "16")
                A: package="app.nzstreamer.plugin.kestrel" (Raw: "app.nzstreamer.plugin.kestrel")
                A: platformBuildVersionCode=36
                A: platformBuildVersionName=16
                  E: uses-sdk (line=3)
                    A: http://schemas.android.com/apk/res/android:minSdkVersion(0x0101020c)=26
                    A: http://schemas.android.com/apk/res/android:targetSdkVersion(0x01010270)=36
                  E: application (line=5)
                    A: http://schemas.android.com/apk/res/android:hasCode(0x0101000c)=true
                    A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true
                      E: meta-data (line=6)
                        A: http://schemas.android.com/apk/res/android:name(0x01010003)="nzs.plugin.id" (Raw: "nzs.plugin.id")
                        A: http://schemas.android.com/apk/res/android:value(0x01010024)="kestrel" (Raw: "kestrel")
                      E: meta-data (line=7)
                        A: http://schemas.android.com/apk/res/android:name(0x01010003)="nzs.plugin.entry" (Raw: "nzs.plugin.entry")
                        A: http://schemas.android.com/apk/res/android:value(0x01010024)="app.nzstreamer.plugin.kestrel.KestrelPlugin" (Raw: "app.nzstreamer.plugin.kestrel.KestrelPlugin")
                      E: meta-data (line=8)
                        A: http://schemas.android.com/apk/res/android:name(0x01010003)="nzs.plugin.api.major" (Raw: "nzs.plugin.api.major")
                        A: http://schemas.android.com/apk/res/android:value(0x01010024)=1
                      E: meta-data (line=9)
                        A: http://schemas.android.com/apk/res/android:name(0x01010003)="nzs.plugin.api.minor" (Raw: "nzs.plugin.api.minor")
                        A: http://schemas.android.com/apk/res/android:value(0x01010024)=0
                      E: meta-data (line=10)
                        A: http://schemas.android.com/apk/res/android:name(0x01010003)="nzs.plugin.libs" (Raw: "nzs.plugin.libs")
                        A: http://schemas.android.com/apk/res/android:value(0x01010024)="kotlin-stdlib=2.3.21,kotlinx-coroutines=1.11.0,compose-bom=2026.06.01" (Raw: "kotlin-stdlib=2.3.21,kotlinx-coroutines=1.11.0,compose-bom=2026.06.01")
        """.trimIndent() + "\n"

        // aapt2 dump resources of an APK with no resources.
        val RESOURCES = "Binary APK\n"
    }
}
