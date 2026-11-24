package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.plugin.PluginApi
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/**
 * The checks a plugin's file passes before any of its code loads (L583, L584, L580): on a download before it is stored,
 * and on the stored copy before every load. Each refusal says why in the Plugins page's words. Nothing of the file is
 * trusted until its signer is the pin: a check that can't be made refuses.
 */
class PluginVerifier(
    private val reader: ArchiveReader,
    private val compat: PluginCompat = PluginCompat(),
    private val trusted: () -> Set<String> = PluginTrust::signers,
) {
    fun verify(apk: File, expected: Expectation): Verdict {
        // The id goes into the package's name and the entry's prefix: one outside the rule names no plugin.
        if (!PluginIds.valid(expected.id)) return Verdict.Refused(Refusal.Package)
        // Android 14 refuses code from a file the app could write; ours are written read-only (L616).
        if (expected.stored && apk.canWrite()) return Verdict.Refused(Refusal.Writable)
        // Before anything parses it: the bytes are the ones recorded at install, or the list's.
        val sha256 = sha256Of(apk) ?: return Verdict.Refused(Refusal.Unreadable)
        if (sha256 != expected.sha256) return Verdict.Refused(Refusal.Hash)
        val archive = try {
            reader.read(apk)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return Verdict.Refused(Refusal.Unreadable)
        val signer = oneSigner(archive.signers) ?: return Verdict.Refused(Refusal.Signers)
        if (signer !in trusted()) return Verdict.Refused(Refusal.Signer)
        val prefix = PluginIds.packageOf(expected.id)
        if (archive.packageName != prefix) return Verdict.Refused(Refusal.Package)
        if (archive.meta.string(PluginApi.META_ID) != expected.id) return Verdict.Refused(Refusal.Id)
        if (archive.versionCode != expected.versionCode) return Verdict.Refused(Refusal.VersionCode)
        if (archive.versionCode < expected.highestLoaded) return Verdict.Refused(Refusal.Older)
        val entry = archive.meta.string(PluginApi.META_ENTRY)
            ?.takeIf { it.startsWith("$prefix.") && it.length > prefix.length + 1 && !it.endsWith(".") }
            ?: return Verdict.Refused(Refusal.Entry)
        val major = archive.meta.int(PluginApi.META_API_MAJOR) ?: return Verdict.Refused(Refusal.Stamp)
        val minor = archive.meta.int(PluginApi.META_API_MINOR) ?: return Verdict.Refused(Refusal.Stamp)
        if (!compat.accepts(major, minor, archive.meta.string(PluginApi.META_LIBS))) return Verdict.Refused(Refusal.Incompatible)
        if (archive.hasResources) return Verdict.Refused(Refusal.Resources)
        return Verdict.Accepted(
            PluginFacts(expected.id, archive.versionCode, archive.versionName ?: archive.versionCode.toString(), entry, sha256, signer),
        )
    }

    /**
     * The archive's one signer: signingInfo's current certificate where Android gave it (API 28 on), else its one
     * certificate in signatures; null where there isn't exactly one.
     */
    private fun oneSigner(signers: ArchiveSigners): String? {
        val info = signers.signingInfo ?: return signers.signatures.singleOrNull()
        return if (info.multipleSigners) null else info.certificates.lastOrNull()
    }

    private fun sha256Of(file: File): String? = try {
        Sha256.of(file)
    } catch (e: IOException) {
        null
    }
}

/**
 * What the file must be: plugin [id]'s version [versionCode] with the SHA-256 [sha256] (the list's for a download, the one
 * recorded at install for a stored copy), at or above the highest of its versions that ever loaded ([highestLoaded]: a
 * version never goes down). A [stored] copy must also be read-only.
 */
class Expectation(val id: String, val sha256: String, val versionCode: Long, val highestLoaded: Long, val stored: Boolean)

sealed interface Verdict {
    class Accepted(val facts: PluginFacts) : Verdict

    class Refused(val refusal: Refusal) : Verdict
}

/** A plugin's file as the checks found it: its [entry] class is under its package, its [signer] the trusted one. */
class PluginFacts(
    val id: String,
    val versionCode: Long,
    val versionName: String,
    val entry: String,
    val sha256: String,
    val signer: String,
)

/**
 * Why a plugin's file was refused, with the Plugins page's words: [reason] ends a stored plugin's "Couldn't start:
 * <reason>.", and [installLine] is a failed install's line. Logs name the refusal by its name alone.
 */
enum class Refusal(private val words: Words) {
    /** The archive couldn't be read, or Android couldn't check its signature: it fails closed. */
    Unreadable(Words.Signer),

    /** A stored copy that can be written to, which Android 14 refuses to load and which may have changed since. */
    Writable(Words.Changed),

    /** Its SHA-256 isn't the one recorded at install, or the list's. */
    Hash(Words.Changed),

    /** Not exactly one signer. */
    Signers(Words.Signer),

    /** Its signer isn't NZStreamer's. */
    Signer(Words.Signer),

    /** Its package isn't app.nzstreamer.plugin.<id>, or the id is outside the rule. */
    Package(Words.Changed),

    /** Its nzs.plugin.id isn't its id. */
    Id(Words.Changed),

    /** Its versionCode isn't the one expected. */
    VersionCode(Words.Changed),

    /** Its versionCode is below the highest of the plugin's that ever loaded. */
    Older(Words.Changed),

    /** It names no entry class, or one outside its package. */
    Entry(Words.OtherVersion),

    /** Its API stamp is missing, or isn't two integers. */
    Stamp(Words.OtherVersion),

    /** Its API or a library it was built against isn't one this NZStreamer runs (L584). */
    Incompatible(Words.OtherVersion),

    /** It carries resources (L580). */
    Resources(Words.OtherVersion),
    ;

    val reason: String get() = words.reason
    val installLine: String get() = words.installLine

    private enum class Words(val reason: String, val installLine: String) {
        Changed("its file changed after it was installed", "The file doesn't match the list, so nothing was installed."),
        Signer("it isn't signed by NZStreamer", "It isn't signed by NZStreamer, so nothing was installed."),
        OtherVersion("it was made for another NZStreamer version", "It was made for another NZStreamer version, so nothing was installed."),
    }
}
