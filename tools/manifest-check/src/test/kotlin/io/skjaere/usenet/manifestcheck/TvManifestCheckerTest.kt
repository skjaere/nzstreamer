package io.skjaere.usenet.manifestcheck

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class TvManifestCheckerTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    private val ready = fixture("tv-ready.xml")

    /** The TV-ready fixture with [old] replaced by [new]; [old] must be in it once. */
    private fun ready(old: String, new: String): String {
        require(ready.split(old).size == 2) { "\"$old\" is not in the fixture once" }
        return ready.replace(old, new)
    }

    private fun check(manifest: String) = TvManifestChecker.check(manifest)

    @Test
    fun `a manifest for phones and TVs passes`() {
        assertEquals(emptyList(), check(ready))
    }

    @Test
    fun `the phone app's manifest is reported for everything a TV needs`() {
        assertEquals(
            listOf(
                "uses-feature android.software.leanback is not declared: Play doesn't offer the app to TVs",
                "uses-feature android.hardware.touchscreen is not declared required=\"false\": Play takes it as required, and TVs have none",
                "uses-feature android.hardware.faketouch is not declared required=\"false\": Play takes it as required, and TVs have none",
                "no exported activity has MAIN with LEANBACK_LAUNCHER: the TV launcher doesn't list the app",
                "no android:banner on the application: the TV launcher has no banner to show",
            ),
            check(fixture("phone-only.xml")),
        )
    }

    @Test
    fun `leanback required would keep the app off phones`() {
        val manifest = ready(
            "android:name=\"android.software.leanback\"\n        android:required=\"false\"",
            "android:name=\"android.software.leanback\"\n        android:required=\"true\"",
        )
        assertEquals(listOf("uses-feature android.software.leanback is required: Play offers the app to TVs only"), check(manifest))
    }

    @Test
    fun `a feature a library declares required is reported, whether it says so or by default`() {
        val manifest = ready(
            "<uses-feature android:glEsVersion=\"0x00020000\" />",
            """<uses-feature android:name="android.hardware.camera.any" />
               <uses-feature android:name="android.hardware.nfc" android:required="true" />
               <uses-feature android:name="android.hardware.wifi" android:required="false" />
               <uses-feature android:glEsVersion="0x00020000" />""",
        )
        assertEquals(
            listOf(
                "uses-feature android.hardware.camera.any is required: devices without it, TVs among them, can't install the app",
                "uses-feature android.hardware.nfc is required: devices without it, TVs among them, can't install the app",
            ),
            check(manifest),
        )
    }

    @Test
    fun `a permission that implies hardware is reported unless the feature is declared not required`() {
        val camera = ready(
            "<uses-permission android:name=\"android.permission.INTERNET\" />",
            """<uses-permission android:name="android.permission.INTERNET" />
               <uses-permission android:name="android.permission.CAMERA" />
               <uses-permission-sdk-23 android:name="android.permission.RECORD_AUDIO" />""",
        )
        assertEquals(
            listOf(
                "uses-permission android.permission.CAMERA implies uses-feature android.hardware.camera, required: declare it required=\"false\"",
                "uses-permission android.permission.CAMERA implies uses-feature android.hardware.camera.autofocus, required: declare it required=\"false\"",
                "uses-permission android.permission.RECORD_AUDIO implies uses-feature android.hardware.microphone, required: declare it required=\"false\"",
            ),
            check(camera),
        )
        val declared = camera.replace(
            "<uses-feature android:glEsVersion=\"0x00020000\" />",
            """<uses-feature android:name="android.hardware.camera" android:required="false" />
               <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
               <uses-feature android:name="android.hardware.microphone" android:required="false" />""",
        )
        assertEquals(emptyList(), check(declared))
    }

    @Test
    fun `location permissions imply hardware only for apps that target API 20 or lower`() {
        val location = ready(
            "<uses-permission android:name=\"android.permission.INTERNET\" />",
            "<uses-permission android:name=\"android.permission.INTERNET\" />\n<uses-permission android:name=\"android.permission.ACCESS_FINE_LOCATION\" />",
        )
        assertEquals(emptyList(), check(location))
        assertEquals(
            listOf(
                "uses-permission android.permission.ACCESS_FINE_LOCATION implies uses-feature android.hardware.location, required: declare it required=\"false\"",
                "uses-permission android.permission.ACCESS_FINE_LOCATION implies uses-feature android.hardware.location.gps, required: declare it required=\"false\"",
            ),
            check(location.replace("android:targetSdkVersion=\"36\"", "android:targetSdkVersion=\"20\"")),
        )
    }

    /**
     * What Google Cast adds to the merged manifest (C1's debug and bench builds; every build's from C2): its service, the two
     * foreground-service permissions, Play services' version, the Cast framework's receiver, service and query. None implies
     * a hardware feature, so C2's release merge stays TV-clean (L72); a TV never starts Cast, which is the app's own gate.
     */
    @Test
    fun `Google Cast's manifest entries keep the app on TVs`() {
        val manifest = ready(
            "<uses-permission android:name=\"android.permission.INTERNET\" />",
            """<uses-permission android:name="android.permission.INTERNET" />
               <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
               <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
               <queries>
                   <package android:name="com.google.android.gms.policy_cast_dynamite" />
               </queries>""",
        ).replace(
            "</application>",
            """    <service
                   android:name="io.skjaere.usenet.app.cast.CastService"
                   android:exported="true"
                   android:foregroundServiceType="mediaPlayback" >
                   <intent-filter>
                       <action android:name="androidx.media3.session.MediaSessionService" />
                   </intent-filter>
               </service>
               <receiver
                   android:name="com.google.android.gms.cast.framework.media.MediaIntentReceiver"
                   android:exported="false" />
               <service
                   android:name="com.google.android.gms.cast.framework.ReconnectionService"
                   android:exported="false" />
               <meta-data
                   android:name="com.google.android.gms.version"
                   android:value="@integer/google_play_services_version" />
           </application>""",
        )
        assertEquals(emptyList(), check(manifest))
    }

    @Test
    fun `the TV launcher's activity must be exported`() {
        val manifest = ready(
            "android:name=\"io.skjaere.usenet.app.MainActivity\"\n            android:exported=\"true\"",
            "android:name=\"io.skjaere.usenet.app.MainActivity\"\n            android:exported=\"false\"",
        )
        assertEquals(listOf("no exported activity has MAIN with LEANBACK_LAUNCHER: the TV launcher doesn't list the app"), check(manifest))
    }

    @Test
    fun `MAIN and LEANBACK_LAUNCHER count only in the same intent filter`() {
        val manifest = ready(
            "<action android:name=\"android.intent.action.MAIN\" />\n                <category android:name=\"android.intent.category.LEANBACK_LAUNCHER\" />",
            "<action android:name=\"android.intent.action.VIEW\" />\n                <category android:name=\"android.intent.category.LEANBACK_LAUNCHER\" />",
        )
        assertEquals(listOf("no exported activity has MAIN with LEANBACK_LAUNCHER: the TV launcher doesn't list the app"), check(manifest))
    }

    @Test
    fun `a banner on the TV launcher's activity serves, none at all is reported`() {
        val onActivity = ready("android:banner=\"@drawable/tv_banner\"\n        ", "")
            .replace("android:name=\"io.skjaere.usenet.app.MainActivity\"", "android:name=\"io.skjaere.usenet.app.MainActivity\"\n            android:banner=\"@drawable/tv_banner\"")
        assertEquals(emptyList(), check(onActivity))
        assertEquals(
            listOf("no android:banner on the application or on io.skjaere.usenet.app.MainActivity: the TV launcher has no banner to show"),
            check(ready("android:banner=\"@drawable/tv_banner\"\n        ", "")),
        )
    }

    @Test
    fun `a portrait-only activity is reported, a landscape or free one is not`() {
        listOf("portrait", "sensorPortrait", "reversePortrait", "userPortrait").forEach { orientation ->
            assertEquals(
                listOf("io.skjaere.usenet.app.PlayerActivity is portrait only (screenOrientation=\"$orientation\"): Play takes it to need a portrait screen, which TVs lack"),
                check(ready("android:screenOrientation=\"sensorLandscape\"", "android:screenOrientation=\"$orientation\"")),
                orientation,
            )
        }
        listOf("landscape", "fullSensor", "unspecified", "locked").forEach { orientation ->
            assertEquals(emptyList(), check(ready("android:screenOrientation=\"sensorLandscape\"", "android:screenOrientation=\"$orientation\"")), orientation)
        }
    }

    @Test
    fun `the tool reads the manifest from a file and writes its report`(@TempDir dir: File) {
        val manifest = File(dir, "AndroidManifest.xml").apply { writeText(fixture("phone-only.xml")) }
        val report = File(dir, "out/report.txt")
        assertEquals(1, run(arrayOf("--manifest", manifest.path, "--report", report.path)))
        val text = report.readText()
        assertEquals(true, text.startsWith("AndroidManifest.xml: what a TV needs is missing:\n"), text)
        assertEquals(true, "  uses-feature android.software.leanback is not declared" in text, text)

        manifest.writeText(ready)
        assertEquals(0, run(arrayOf("--manifest", manifest.path, "--report", report.path)))
        assertEquals(true, report.readText().startsWith("AndroidManifest.xml: ready for TVs"), report.readText())
    }
}
