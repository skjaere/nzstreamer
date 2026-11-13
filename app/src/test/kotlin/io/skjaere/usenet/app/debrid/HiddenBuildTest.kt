package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.app.BuildConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * L192, L193: the build hides debrid exactly when it was asked to. The expectation comes from outside, never from the
 * flag: -PexpectHideDebrid, which the app's unit-test tasks pass on as the system property expectHideDebrid (a comparison
 * of the flag with [DebridGate.shown] alone would pass whether or not -PhideDebrid or -PwithDebrid reached the build).
 * The lead runs it four times, each reading a skip as a failure:
 *
 *     ./gradlew :app:testReleaseUnitTest --tests '*HiddenBuildTest' -PexpectHideDebrid=false
 *     ./gradlew :app:testReleaseUnitTest --tests '*HiddenBuildTest' -PhideDebrid=true -PexpectHideDebrid=true
 *     ./gradlew -PbetaBuild=10001 :app:testBetaUnitTest --tests '*HiddenBuildTest' -PexpectHideDebrid=true
 *     ./gradlew -PbetaBuild=10001 -PwithDebrid=true :app:testBetaUnitTest --tests '*HiddenBuildTest' -PexpectHideDebrid=false
 *
 * 10001 is a local beta's number: version.txt's code plus one (0.1.0 is 10000; VersionCodeTest gives the formula).
 * A beta needs a key: the beta key, or for these runs a throwaway one, given as app/build.gradle.kts says (-PbetaSigning
 * or the four beta* properties). Without the property it is skipped, as no other run has an expectation to compare with.
 */
@EnabledIfSystemProperty(named = "expectHideDebrid", matches = ".*")
class HiddenBuildTest {
    @Test
    fun `the build hides debrid as it was asked to, and the gate follows it`() {
        val expected = when (val given = System.getProperty("expectHideDebrid")) {
            "true" -> true
            "false" -> false
            else -> fail("-PexpectHideDebrid takes true or false, not '$given'")
        }
        assertEquals(expected, BuildConfig.HIDE_DEBRID, "BuildConfig.HIDE_DEBRID")
        assertEquals(!expected, DebridGate.shown, "DebridGate.shown")
    }
}
