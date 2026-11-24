package io.skjaere.usenet.app.plugins

/**
 * Which build type's unit tests run, as the test task names it (app/build.gradle.kts passes unitTestBuildType to every
 * unit-test task): the expectation of a debug-only gate comes from outside the build's own flag, so a gate that read the
 * wrong flag, or none, fails in one of the two runs.
 */
internal object UnitTestBuild {
    val type: String = System.getProperty("unitTestBuildType")
        ?: error("The app's unit-test tasks pass unitTestBuildType; run them through Gradle")

    /** A debug build's unit tests: the only build whose BuildConfig.DEBUG is true (bench and the betas start from release). */
    val debug: Boolean get() = type == "debug"
}
