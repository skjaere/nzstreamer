package io.skjaere.usenet.app

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app's version comes from version.txt, which release-please keeps (L24, L29). Its versionCode is
 * ((MAJOR * 100 + MINOR) * 100 + PATCH) * 100, so each release's code is above the one before, with room for 99 local
 * betas between two releases. This reads version.txt itself and checks the build it runs in: debug, release and bench
 * carry the version and its code. A beta carries the code itself, named as the version (the release's own APK), or one
 * of the 99 codes above it, named <version>-beta.<code>. BuildConfig's fields are read at run time: their constants,
 * compiled into this class, could be an earlier build's.
 */
class VersionCodeTest {
    private val version = File("../version.txt").readText().trim()

    private val base: Long = run {
        val (major, minor, patch) = Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""").matchEntire(version)
            ?.groupValues?.drop(1)?.map { it.toLong() }
            ?: fail("version.txt holds '$version', not MAJOR.MINOR.PATCH")
        assertTrue(minor < 100 && patch < 100, "version.txt's MINOR and PATCH must be under 100: $version")
        ((major * 100 + minor) * 100 + patch) * 100
    }

    private fun buildConfig(field: String): Any? = BuildConfig::class.java.getField(field).get(null)

    @Test
    fun `the build carries version txt's version and the code made from it`() {
        val code = (buildConfig("VERSION_CODE") as Int).toLong()
        val name = buildConfig("VERSION_NAME")
        when (val type = buildConfig("BUILD_TYPE")) {
            "debug", "release", "bench" -> {
                assertEquals(base, code, "the $type build's versionCode")
                assertEquals(version, name, "the $type build's versionName")
            }
            "beta" -> {
                assertTrue(code in base..base + 99, "a beta's versionCode is $code, outside $base..${base + 99}")
                assertEquals(if (code == base) version else "$version-beta.$code", name, "beta $code's versionName")
            }
            else -> fail("a build type this test doesn't know: $type")
        }
    }
}
