package io.skjaere.usenet.plugincheck.fixture

/**
 * ApiDumpTest's classes, compiled with the tests so they carry Kotlin's real @Metadata: what a plugin could call of them,
 * and what the class files make public that Kotlin keeps to the module or the class.
 */
open class Harbor internal constructor(val name: String) {
    constructor() : this("Copper Crown")

    /** Its setter is internal: Kotlin compiles it public, under a name mangled with the module's. */
    var tide: Int = 0
        internal set

    protected open fun moor(): String = name

    private fun hidden(): Int = 1

    internal fun keeper(): Int = hidden()

    /** A default argument: Kotlin adds a synthetic sail$default beside it. */
    fun sail(knots: Int = 3): Int = knots

    companion object {
        const val PIER = 4
    }
}

/** Internal at the top level: public static in the file's class, under its own name. */
internal fun lighthouse(): String = "Harbor Lights"

fun beacon(): String = lighthouse()

internal class Keeper(val lamp: Int)
