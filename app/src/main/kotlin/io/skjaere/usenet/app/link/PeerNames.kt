package io.skjaere.usenet.app.link

/**
 * Device names on Play on TV's link (L28). This device's own is [own]. A name from a peer (its hello, or a TV's mDNS
 * instance name) is untrusted: [clean] takes out control and format characters, bidi overrides among them (they would
 * turn a name round on screen, and what follows it), and line breaks (they would spill over a row), cuts it to [MAX]
 * code points, and gives a fallback ("Phone" or "TV") for one left empty.
 */
object PeerNames {
    /** The most a name is shown with, in code points. */
    const val MAX = 40

    fun clean(raw: String?, fallback: String): String {
        if (raw == null) return fallback
        val kept = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val point = raw.codePointAt(i)
            i += Character.charCount(point)
            if (Character.getType(point) !in DROPPED) kept.appendCodePoint(point)
        }
        val trimmed = kept.toString().trim()
        val cut = if (trimmed.codePointCount(0, trimmed.length) > MAX) trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX)).trimEnd() else trimmed
        return cut.ifEmpty { fallback }
    }

    /**
     * This device's name (L28): the one the user gave it in the system's settings ([deviceName], Settings.Global's
     * DEVICE_NAME), else its maker and model, at most [MAX] characters, cleaned as a peer's would be; [fallback] without
     * either. A TV keeps the name it gives itself: there is no renaming it here.
     */
    fun own(deviceName: String?, manufacturer: String, model: String, fallback: String = "TV"): String =
        clean(deviceName, "").ifEmpty { clean("$manufacturer $model", fallback) }

    /** Control (line feeds and returns among them), format (bidi, zero-width), line and paragraph separators, and half a pair. */
    private val DROPPED = setOf(Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE)
        .map { it.toInt() }.toSet()
}
