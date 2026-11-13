package io.skjaere.usenet.discover

import java.text.Collator
import java.util.Locale

/**
 * Twenty original languages common on TMDB, by TMDB's own codes: "cn" is its Cantonese (Hong Kong's films), a filter of
 * its own beside "zh". A fixed list: TMDB's has about 180, too long for a TV's menu.
 */
private val CODES = listOf("en", "fr", "de", "es", "it", "pt", "nl", "sv", "da", "no", "fi", "pl", "ru", "tr", "hi", "ja", "ko", "zh", "cn", "th")

/** [CODES] named in [locale], in its alphabetical order. */
internal fun commonLanguages(locale: Locale): List<Language> {
    val collator = Collator.getInstance(locale)
    return CODES.map { code ->
        // Locales know Cantonese by its ISO 639-3 code.
        val name = Locale.forLanguageTag(if (code == "cn") "yue" else code).getDisplayLanguage(locale)
        Language(code, name.replaceFirstChar { it.titlecase(locale) })
    }.sortedWith(compareBy(collator) { it.name })
}
