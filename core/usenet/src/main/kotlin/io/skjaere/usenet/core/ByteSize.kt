package io.skjaere.usenet.core

/**
 * A size as the app shows it, in decimal units: whole KB under 1 MB, MB with one decimal under 10 MB ("2.3 MB"), whole MB
 * under 1 GB, then GB with one decimal. Rounded half up, in the unit the rounded figure reads in: 999.6 KB is "1.0 MB",
 * never "1000 KB". The digits follow the default locale. The play sheet's Fetching NZB and the app's sizes share it.
 */
fun formatSize(bytes: Long): String {
    val kb = (bytes + 500) / 1_000
    if (kb < 1_000) return "%d KB".format(kb)
    val tenthsOfMb = (bytes + 50_000) / 100_000
    if (tenthsOfMb < 100) return "%.1f MB".format(tenthsOfMb / 10.0)
    val mb = (bytes + 500_000) / 1_000_000
    if (mb < 1_000) return "%d MB".format(mb)
    return "%.1f GB".format((bytes + 50_000_000) / 100_000_000 / 10.0)
}
