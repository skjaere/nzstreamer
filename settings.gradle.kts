// dl.google.com is unreachable from some networks for non-HTTP/2 clients (Gradle's
// HTTP client among them). Set googleMavenMirror=<url> in ~/.gradle/gradle.properties
// to use a mirror *instead of* google(): a repository that times out aborts resolution
// in Gradle, so it cannot be kept as a fallback. (pluginManagement is compiled in
// isolation, so the property is read in both places.)
pluginManagement {
    repositories {
        // Maven Central first: a mirror outage (5xx) would otherwise abort resolution of
        // artifacts that Central serves anyway.
        mavenCentral()
        settings.providers.gradleProperty("googleMavenMirror").orNull?.let { maven(it) } ?: google()
        gradlePluginPortal()
    }
}

// The usenet libraries come from JitPack at the releases pinned in gradle/libs.versions.toml. To build against your own
// checkouts of them, pass -PusenetLibsDir=<folder> naming the folder that holds all four (see the end of this file).
val usenetLibsDir: File? = providers.gradleProperty("usenetLibsDir").orNull?.let { dir ->
    require(dir.isNotBlank()) {
        "-PusenetLibsDir is empty: name the folder that holds the four library checkouts, " +
            "or leave the property out to build from JitPack"
    }
    file(dir)
}
// useLocalLibs is no longer read. A build given it would otherwise take JitPack's releases without a word.
require(providers.gradleProperty("useLocalLibs").orNull == null) {
    "useLocalLibs is no longer read: pass -PusenetLibsDir=<folder> to build against local checkouts, " +
        "or nothing to build from JitPack's releases"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        providers.gradleProperty("googleMavenMirror").orNull?.let { maven(it) } ?: google()
        // The usenet libraries come from JitPack alone, and JitPack gives nothing else: no other repository is asked
        // for them, so none can serve another build of them.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") { name = "JitPack" } }
            filter { includeGroup("com.github.skjaere") }
        }
        // Local mode only: a build from JitPack never depends on what sits in the local Maven repository.
        if (usenetLibsDir != null) mavenLocal()
    }
}

rootProject.name = "nzstreamer"

include(":native:rapidyenc")
include(":tools:mock-seed")
include(":tools:api-check")
include(":tools:native-check")
include(":tools:manifest-check")
include(":tools:feature-check")
include(":core:usenet")
include(":core:releases")
include(":core:newznab")
include(":core:debrid")
include(":core:handoff")
include(":core:link")
include(":core:serve")
include(":core:data")
include(":core:metadata")
include(":core:discover")
include(":core:search")
include(":core:logging")
include(":core:player")
include(":app")

// Local checkouts of the usenet libraries, only with -PusenetLibsDir. All four must be there: a missing one fails the
// build, so a build is never half local. The wrapper's folder may carry its repository's name or yenc_kotlin_wrapper.
// The first of them that is there is taken, built or not, as ktor-nntp-client's own include of the wrapper takes it, so
// both include the same folder; one that holds no build fails here, by name, rather than in that include.
usenetLibsDir?.let { libsDir ->
    require(libsDir.isDirectory) { "-PusenetLibsDir=$libsDir is not a folder" }
    fun File.holdsBuild() = resolve("settings.gradle.kts").isFile || resolve("settings.gradle").isFile
    val checkouts = listOf(
        "rapidyenc-kotlin-wrapper" to listOf("rapidyenc-kotlin-wrapper", "yenc_kotlin_wrapper"),
        "ktor-nntp-client" to listOf("ktor-nntp-client"),
        "kotlin-compression-utils" to listOf("kotlin-compression-utils"),
        "nzb-streamer" to listOf("nzb-streamer"),
    ).map { (library, folders) -> Triple(library, folders, folders.map(libsDir::resolve).firstOrNull { it.exists() }) }
    val missing = checkouts.filter { it.third?.holdsBuild() != true }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "-PusenetLibsDir=$libsDir holds no checkout of " +
                missing.joinToString("; ") { (library, folders, found) ->
                    if (found == null) {
                        "$library (a folder named ${folders.joinToString(" or ")})"
                    } else {
                        "$library (its folder ${found.name} holds no settings.gradle.kts or settings.gradle)"
                    }
                } +
                ". Name a folder that holds all four libraries, or leave the property out to build from JitPack.",
        )
    }
    checkouts.forEach { (library, _, checkout) ->
        includeBuild(checkout!!) {
            dependencySubstitution {
                substitute(module("com.github.skjaere:$library")).using(project(":"))
            }
        }
    }
}
