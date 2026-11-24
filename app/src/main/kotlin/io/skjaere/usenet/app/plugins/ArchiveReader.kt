package io.skjaere.usenet.app.plugins

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.util.zip.ZipFile

/**
 * What [PluginVerifier] reads of a plugin's APK file: Android's own reading of the archive, through a seam, so the JVM
 * tests can give it as every Android version from 8 on gives it (L637: our emulators run 14 and 16 alone). Null, or a
 * throw, for an archive that can't be read: the verifier refuses it.
 */
fun interface ArchiveReader {
    fun read(apk: File): ArchiveFacts?
}

/** The archive as Android read it: nothing here has been checked yet. */
class ArchiveFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signers: ArchiveSigners,
    val meta: ArchiveMeta,
    /** Whether any entry lies under res/: plugins carry no resources (L580). */
    val hasResources: Boolean,
)

/**
 * The archive's signers, as SHA-256 digests of their certificates (lower-case hex, as tools/beta-cert.sha256 holds the
 * beta key's): [signingInfo] from API 28 on, [signatures] as API 26 to 29 give them.
 */
class ArchiveSigners(val signingInfo: SigningFacts?, val signatures: List<String>)

/**
 * signingInfo's answer: with [multipleSigners], [certificates] are the APK's signers; else its signing history, oldest
 * first, the current certificate last (a key rotated with APK signature scheme v3).
 */
class SigningFacts(val multipleSigners: Boolean, val certificates: List<String>)

/** The manifest's meta-data, read typed: a value of another type reads as none, as Bundle's getInt and getString give it. */
interface ArchiveMeta {
    fun int(name: String): Int?
    fun string(name: String): String?
}

/** The flags getPackageArchiveInfo needs on each Android version (L583). */
object ArchiveFlags {
    // PackageManager's GET_SIGNATURES, GET_SIGNING_CERTIFICATES (API 28) and GET_META_DATA, by value: the second is a
    // constant of API 28's, read on older versions too.
    private const val SIGNATURES = 0x40
    private const val SIGNING_CERTIFICATES = 0x08000000
    private const val META_DATA = 0x80

    /**
     * An archive's certificates are collected only with GET_SIGNATURES up to Android 10 (API 29), and its signingInfo
     * only with GET_SIGNING_CERTIFICATES from 9 (API 28): both on 9 and 10, each alone on either side.
     */
    fun forSdk(sdk: Int): Int = when {
        sdk < 28 -> SIGNATURES
        sdk < 30 -> SIGNATURES or SIGNING_CERTIFICATES
        else -> SIGNING_CERTIFICATES
    } or META_DATA
}

/** A certificate's SHA-256 digest in lower-case hex, as apksigner and tools/beta-cert.sha256 give it. */
object SignerDigest {
    fun of(certificate: ByteArray): String = Sha256.of(certificate)
}

/** Android's own reading of an archive: getPackageArchiveInfo with [ArchiveFlags], on this device's version. */
class AndroidArchiveReader(private val packageManager: PackageManager, private val sdk: Int = Build.VERSION.SDK_INT) : ArchiveReader {
    override fun read(apk: File): ArchiveFacts? {
        @Suppress("DEPRECATION") // The flags' Int form: PackageInfoFlags is API 33's.
        val info = packageManager.getPackageArchiveInfo(apk.path, ArchiveFlags.forSdk(sdk)) ?: return null
        val hasResources = ZipFile(apk).use { zip -> zip.entries().asSequence().any { it.name.startsWith("res/") } }
        return ArchiveFacts(
            packageName = info.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            versionName = info.versionName,
            signers = ArchiveSigners(signingInfoOf(info), signaturesOf(info)),
            meta = BundleMeta(info.applicationInfo?.metaData),
            hasResources = hasResources,
        )
    }

    private fun signingInfoOf(info: PackageInfo): SigningFacts? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val signing = info.signingInfo ?: return null
        return if (signing.hasMultipleSigners()) {
            SigningFacts(multipleSigners = true, certificates = signing.apkContentsSigners.orEmpty().map { SignerDigest.of(it.toByteArray()) })
        } else {
            SigningFacts(multipleSigners = false, certificates = signing.signingCertificateHistory.orEmpty().map { SignerDigest.of(it.toByteArray()) })
        }
    }

    @Suppress("DEPRECATION") // signatures: how Android 8 to 10 give an archive's certificates.
    private fun signaturesOf(info: PackageInfo): List<String> = info.signatures.orEmpty().map { SignerDigest.of(it.toByteArray()) }

    /** Bundle's typed getters: a missing value, or one of another type (a float where an integer goes), reads as none. */
    private class BundleMeta(private val bundle: Bundle?) : ArchiveMeta {
        override fun int(name: String): Int? = bundle?.getInt(name, NONE)?.takeIf { it != NONE }

        override fun string(name: String): String? = bundle?.getString(name)

        private companion object {
            // No stamp is this low: the verifier would refuse it anyway.
            const val NONE = Int.MIN_VALUE
        }
    }
}
