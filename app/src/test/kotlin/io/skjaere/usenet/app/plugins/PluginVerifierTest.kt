package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.plugin.PluginApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * L583, L584, L580: what NZStreamer checks of a plugin's file before any of its code loads, read through the archive seam
 * as Android 8 to 16 give it (L637: API 26-29 run on none of our emulators, so their forms are checked here). Each refusal
 * carries the Plugins page's words.
 */
class PluginVerifierTest {
    @TempDir
    lateinit var dir: File

    private val hostLibs = "kotlin-stdlib=2.3.21,kotlinx-coroutines=1.11.0,kotlinx-serialization-json=1.11.0," +
        "compose-bom=2026.06.01,lifecycle-viewmodel-compose=2.10.0,activity-compose=1.13.0,slf4j-api=2.0.20"

    private val apk: File by lazy { File(dir, "plugin.apk").apply { writeBytes("the skeleton's dex, version 2".toByteArray()) } }
    private val sha: String by lazy { MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).joinToString("") { "%02x".format(it) } }

    private fun meta(vararg values: Pair<String, Any?>) = mapOf<String, Any?>(
        PluginApi.META_ID to "skeleton",
        PluginApi.META_ENTRY to "app.nzstreamer.plugin.skeleton.SkeletonPlugin",
        PluginApi.META_API_MAJOR to 1,
        PluginApi.META_API_MINOR to 0,
        PluginApi.META_LIBS to hostLibs,
    ) + values

    private fun facts(
        packageName: String = "app.nzstreamer.plugin.skeleton",
        versionCode: Long = 2,
        signers: ArchiveSigners = ArchiveSigners(SigningFacts(multipleSigners = false, certificates = listOf(PIN)), emptyList()),
        meta: Map<String, Any?> = meta(),
        hasResources: Boolean = false,
    ) = ArchiveFacts(packageName, versionCode, "2", signers, MapMeta(meta), hasResources)

    /** A Bundle's typed getters: a value of another type reads as none, as getInt and getString give it. */
    private class MapMeta(private val values: Map<String, Any?>) : ArchiveMeta {
        override fun int(name: String): Int? = values[name] as? Int
        override fun string(name: String): String? = values[name] as? String
    }

    private var reads = 0

    private fun verifier(facts: ArchiveFacts?, major: Int = 1, minor: Int = 0) =
        PluginVerifier(ArchiveReader { reads++; facts }, PluginCompat(major, minor, hostLibs), trusted = { setOf(PIN) })

    private fun expected(stored: Boolean = true, versionCode: Long = 2, highestLoaded: Long = 1, sha256: String = sha) =
        Expectation(id = "skeleton", sha256 = sha256, versionCode = versionCode, highestLoaded = highestLoaded, stored = stored)

    private fun stored(): File = apk.apply { setReadOnly() }

    private fun refusal(facts: ArchiveFacts?, expected: Expectation = expected(), file: File = stored()): Refusal =
        assertIs<Verdict.Refused>(verifier(facts).verify(file, expected), "$expected").refusal

    @Test
    fun `a file that passes every check is accepted, with what the host needs of it`() {
        val accepted = assertIs<Verdict.Accepted>(verifier(facts()).verify(stored(), expected()))
        with(accepted.facts) {
            assertEquals("skeleton", id)
            assertEquals(2L, versionCode)
            assertEquals("2", versionName)
            assertEquals("app.nzstreamer.plugin.skeleton.SkeletonPlugin", entry)
            assertEquals(sha, sha256)
            assertEquals(PIN, signer)
        }
    }

    @Test
    fun `each refusal, with the Plugins page's words`() {
        val changed = "its file changed after it was installed" to "The file doesn't match the list, so nothing was installed."
        val signer = "it isn't signed by NZStreamer" to "It isn't signed by NZStreamer, so nothing was installed."
        val version = "it was made for another NZStreamer version" to "It was made for another NZStreamer version, so nothing was installed."
        val cases: List<Triple<String, () -> Refusal, Pair<Refusal, Pair<String, String>>>> = listOf(
            Triple("writable", { refusal(facts(), file = apk.apply { setWritable(true) }) }, Refusal.Writable to changed),
            Triple("another SHA-256", { refusal(facts(), expected(sha256 = "0".repeat(64))) }, Refusal.Hash to changed),
            Triple("unreadable", { refusal(null) }, Refusal.Unreadable to signer),
            Triple("another signer", { refusal(facts(signers = ArchiveSigners(SigningFacts(false, listOf(OTHER)), emptyList()))) }, Refusal.Signer to signer),
            Triple("two signers", { refusal(facts(signers = ArchiveSigners(SigningFacts(true, listOf(PIN, OTHER)), emptyList()))) }, Refusal.Signers to signer),
            Triple("another package", { refusal(facts(packageName = "app.nzstreamer.plugin.other")) }, Refusal.Package to changed),
            Triple("another id", { refusal(facts(meta = meta(PluginApi.META_ID to "other"))) }, Refusal.Id to changed),
            Triple("an id stored as a boolean", { refusal(facts(meta = meta(PluginApi.META_ID to true))) }, Refusal.Id to changed),
            Triple("another versionCode", { refusal(facts(versionCode = 3)) }, Refusal.VersionCode to changed),
            Triple("below the highest loaded", { refusal(facts(versionCode = 2), expected(versionCode = 2, highestLoaded = 3)) }, Refusal.Older to changed),
            Triple("no entry", { refusal(facts(meta = meta(PluginApi.META_ENTRY to null))) }, Refusal.Entry to version),
            Triple("no API stamp", { refusal(facts(meta = meta(PluginApi.META_API_MINOR to null))) }, Refusal.Stamp to version),
            Triple("a newer API", { refusal(facts(meta = meta(PluginApi.META_API_MINOR to 1))) }, Refusal.Incompatible to version),
            Triple("a newer library", { refusal(facts(meta = meta(PluginApi.META_LIBS to "kotlin-stdlib=2.4.0"))) }, Refusal.Incompatible to version),
            Triple("no libraries named", { refusal(facts(meta = meta(PluginApi.META_LIBS to null))) }, Refusal.Incompatible to version),
            Triple("a res entry", { refusal(facts(hasResources = true)) }, Refusal.Resources to version),
        )
        cases.forEach { (case, refuse, want) ->
            val (kind, words) = want
            val got = refuse()
            assertEquals(kind, got, case)
            assertEquals(words.first, got.reason, case)
            assertEquals(words.second, got.installLine, case)
        }
        assertEquals(Refusal.entries.toSet(), cases.map { it.third.first }.toSet(), "every refusal has a case")
    }

    @Test
    fun `a stored copy must be read-only, a download is checked before it is stored`() {
        assertEquals(Refusal.Writable, refusal(facts(), file = apk.apply { setWritable(true) }))
        assertIs<Verdict.Accepted>(verifier(facts()).verify(apk.apply { setWritable(true) }, expected(stored = false)))
    }

    @Test
    fun `the SHA-256 is checked before the archive is read`() {
        assertEquals(Refusal.Hash, refusal(facts(signers = ArchiveSigners(null, emptyList())), expected(sha256 = "f".repeat(64))))
        assertEquals(0, reads)
        assertEquals(Refusal.Hash, refusal(facts(), expected(sha256 = sha.uppercase())), "a digest is lower-case hex")
    }

    @Test
    fun `an archive that throws as it is read is unreadable`() {
        val throwing = PluginVerifier(ArchiveReader { throw IOException("not a zip") }, PluginCompat(1, 0, hostLibs), trusted = { setOf(PIN) })
        assertEquals(Refusal.Unreadable, assertIs<Verdict.Refused>(throwing.verify(stored(), expected())).refusal)
    }

    @Test
    fun `the API stamp is two integers, never a float or a string`() {
        assertEquals(Refusal.Stamp, refusal(facts(meta = meta(PluginApi.META_API_MAJOR to 1.0f))), "a float major")
        assertEquals(Refusal.Stamp, refusal(facts(meta = meta(PluginApi.META_API_MINOR to 0.0f))), "a float minor")
        assertEquals(Refusal.Stamp, refusal(facts(meta = meta(PluginApi.META_API_MAJOR to "1.0", PluginApi.META_API_MINOR to null))), "one string")
        assertEquals(Refusal.Stamp, refusal(facts(meta = meta(PluginApi.META_API_MINOR to "0"))), "a minor as a string")
    }

    @Test
    fun `minors are read as integers, so host 1 point 9 refuses 1 point 10 and host 1 point 10 accepts 1 point 9`() {
        val ten = facts(meta = meta(PluginApi.META_API_MINOR to 10))
        val nine = facts(meta = meta(PluginApi.META_API_MINOR to 9))
        assertEquals(Refusal.Incompatible, assertIs<Verdict.Refused>(verifier(ten, major = 1, minor = 9).verify(stored(), expected())).refusal)
        assertIs<Verdict.Accepted>(verifier(nine, major = 1, minor = 10).verify(stored(), expected()))
    }

    @Test
    fun `an entry class must be under the plugin's own package`() {
        listOf(
            "io.skjaere.usenet.app.MainActivity",
            "app.nzstreamer.plugin.other.OtherPlugin",
            "app.nzstreamer.plugin.skeletonx.SkeletonPlugin",
            "app.nzstreamer.plugin.skeleton",
            "app.nzstreamer.plugin.skeleton.",
            "",
        ).forEach { entry -> assertEquals(Refusal.Entry, refusal(facts(meta = meta(PluginApi.META_ENTRY to entry))), entry) }
        assertIs<Verdict.Accepted>(verifier(facts(meta = meta(PluginApi.META_ENTRY to "app.nzstreamer.plugin.skeleton.pages.Entry"))).verify(stored(), expected()))
    }

    @Test
    fun `an id outside the rule is refused before it names a package`() {
        listOf("Skeleton", "1skeleton", "true", "false", "a".repeat(32), "../skeleton", "").forEach { id ->
            val refused = verifier(facts(packageName = "app.nzstreamer.plugin.$id", meta = meta(PluginApi.META_ID to id)))
                .verify(stored(), Expectation(id = id, sha256 = sha, versionCode = 2, highestLoaded = 0, stored = true))
            assertEquals(Refusal.Package, assertIs<Verdict.Refused>(refused, id).refusal, id)
        }
    }

    @Test
    fun `the archive is asked for its signers and meta-data with the flags each Android version needs`() {
        val signatures = 0x40
        val certificates = 0x08000000
        val metaData = 0x80
        mapOf(
            26 to (signatures or metaData),
            27 to (signatures or metaData),
            28 to (signatures or certificates or metaData),
            29 to (signatures or certificates or metaData),
            30 to (certificates or metaData),
            36 to (certificates or metaData),
        ).forEach { (sdk, flags) -> assertEquals(flags, ArchiveFlags.forSdk(sdk), "API $sdk") }
    }

    @Test
    fun `signingInfo has one signer, whose current certificate is the pin`() {
        fun signedBy(info: SigningFacts) = verifier(facts(signers = ArchiveSigners(info, emptyList()))).verify(stored(), expected())
        assertEquals(Refusal.Signers, assertIs<Verdict.Refused>(signedBy(SigningFacts(true, listOf(PIN, OTHER)))).refusal, "several signers")
        assertIs<Verdict.Accepted>(signedBy(SigningFacts(false, listOf(OTHER, PIN))), "a rotated key whose last certificate is the pin")
        assertEquals(Refusal.Signer, assertIs<Verdict.Refused>(signedBy(SigningFacts(false, listOf(PIN, OTHER)))).refusal, "rotated away from the pin")
        assertEquals(Refusal.Signers, assertIs<Verdict.Refused>(signedBy(SigningFacts(false, emptyList()))).refusal, "no certificate")
    }

    @Test
    fun `signatures alone, as Android 8 to 10 give them, hold one certificate, the pin`() {
        fun signedBy(vararg digests: String) = verifier(facts(signers = ArchiveSigners(null, digests.toList()))).verify(stored(), expected())
        assertIs<Verdict.Accepted>(signedBy(PIN))
        assertEquals(Refusal.Signers, assertIs<Verdict.Refused>(signedBy(PIN, OTHER)).refusal, "two")
        assertEquals(Refusal.Signers, assertIs<Verdict.Refused>(signedBy()).refusal, "none")
        assertEquals(Refusal.Signer, assertIs<Verdict.Refused>(signedBy(OTHER)).refusal, "another")
    }

    @Test
    fun `signingInfo wins where both are given`() {
        val both = ArchiveSigners(SigningFacts(false, listOf(OTHER)), listOf(PIN))
        assertEquals(Refusal.Signer, refusal(facts(signers = both)))
    }

    @Test
    fun `a digest is compared whole`() {
        assertEquals(Refusal.Signer, refusal(facts(signers = ArchiveSigners(SigningFacts(false, listOf(PIN.uppercase())), emptyList()))))
        assertEquals(Refusal.Signer, refusal(facts(signers = ArchiveSigners(SigningFacts(false, listOf(PIN.take(63))), emptyList()))))
        assertTrue(SignerDigest.of(byteArrayOf(1, 2, 3)).matches(Regex("[0-9a-f]{64}")))
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", SignerDigest.of(byteArrayOf(1, 2, 3)))
    }

    private companion object {
        val PIN = "ab".repeat(32)
        val OTHER = "cd".repeat(32)
    }
}
