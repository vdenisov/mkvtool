package org.plukh.mkvtool.core

import java.util.Locale
import java.util.MissingResourceException

/**
 * Guessing a language from the words of a folder name or a file-name suffix. A port of the language half
 * of `src/lib/discovery.groovy`, used to describe an external track that carries no tag of its own.
 *
 * The spellings are **derived from CLDR at load time** rather than typed out: for each curated code the
 * JDK supplies the three-letter code, the English name and the language's own name for itself, which is
 * what makes `Русский`, `Español` and `日本語` work as folder names and what keeps it correct as CLDR is
 * updated.
 *
 * **CLDR gives the citation form only**, which is the guesser's real limit — not the length of the code
 * list. A native name arrives as masculine nominative singular (`русский`), so a folder whose adjective
 * agrees with its noun does not match: `Русская озвучка` (feminine, agreeing with *озвучка*) and
 * `Русские субтитры` (plural) are both Russian and both miss. Every language with adjective agreement has
 * the same shape. The result is no guess rather than a wrong one, which is the intended failure: the
 * guess is a convenience, and declining to guess costs the reader nothing they had, while a confident
 * wrong `rus` in a report used to write a config costs them a season. Stem matching (`русск-` plus any
 * ending) would catch it and is deliberately not done — it reintroduces exactly the substring false
 * positives the whole-word rule exists to prevent.
 */

/**
 * The languages worth guessing at. Curated rather than "every language the JDK knows", because obscure
 * two- and three-letter codes collide with ordinary English words — `new` is Newari, `sun` is Sundanese,
 * `no` is Norwegian — and "New subs" is not a Newari release.
 */
private val LANGUAGE_CODES = listOf(
    "ru", "en", "ja", "uk", "de", "fr", "es", "it", "pt", "pl", "zh", "ko",
    "cs", "sk", "hu", "ro", "bg", "sr", "hr", "sl", "lt", "lv", "et", "fi",
    "sv", "da", "no", "nl", "el", "tr", "ar", "he", "fa", "hi", "th", "vi",
)

/**
 * Codes from [LANGUAGE_CODES] that are also ordinary words: a folder called "No subs" must not read as
 * Norwegian, and "UK BluRay" must not read as Ukrainian. Only the bare two-letter code is withheld —
 * every one of these is still matched by its three-letter code and by its name, in English or its own, so
 * the cost of an entry here is close to nothing while the cost of a false positive is a wrong language in
 * a report used to write a config.
 *
 * Scoped to [LANGUAGE_CODES] on purpose: this set is consulted only while that list is walked, so an
 * entry for a code that is never guessed at is dead weight that reads as protection it does not provide.
 * Add the two together.
 */
private val AMBIGUOUS_CODES = setOf(
    "no", // "No subs"
    "it", // English "it"
    "he", // English "he"
    "hi", // English "hi"
    "uk", // "UK BluRay", the region
    "el", // the Spanish article
    "et", // the French conjunction
    "da", // the Italian/Portuguese preposition
)

/**
 * ISO 639-2/B ("bibliographic") codes, which differ from the /T codes the JDK returns. Matroska files in
 * the wild carry these, and so do folder names, so this is what a match reports itself as.
 *
 * Keyed by [LANGUAGE_CODES] entries only, and only where /B actually differs from `getISO3Language` —
 * Serbian's are both `srp`, so it is absent and falls through to the JDK's answer.
 */
private val BIBLIOGRAPHIC = mapOf(
    "de" to "ger", "fr" to "fre", "nl" to "dut", "zh" to "chi", "cs" to "cze",
    "el" to "gre", "fa" to "per", "ro" to "rum", "sk" to "slo",
)

/** Every spelling of every curated language, lower-cased, mapped to the code a match reports. */
private val LANGUAGE_BY_TOKEN: Map<String, String> = buildLanguageTokens()

/** Anything that is not a letter or a digit separates words. Splitting on this keeps Cyrillic and CJK
 *  names intact, which is the point of carrying native names at all. */
private val WORD_SEPARATOR = Regex("""[^\p{L}\p{N}]+""")

/**
 * The language [texts] describe, or null when none of them says.
 *
 * Texts are tried **in order**, so the caller decides precedence: a suffix or the file's own directory
 * describes it better than a category directory three levels up. Within one text, single words are tried
 * first and only then runs of two and three, so a name of more than one word ("norsk bokmål") still
 * matches without a single word of a longer name winning first.
 *
 * Matching is on whole words, never substrings: "Ru subs" and "Rus.subs" are Russian, "Rusubs" is not.
 * Without that rule a short code would fire on any release group or show title that happened to contain
 * the letters.
 */
fun guessLanguage(texts: List<String?>): String? {
    for (text in texts) {
        if (text.isNullOrEmpty()) continue
        val words = text.lowercase(Locale.ROOT).split(WORD_SEPARATOR).filter { it.isNotEmpty() }

        for (word in words) {
            LANGUAGE_BY_TOKEN[word]?.let { return it }
        }
        for (length in 2..3) {
            for (start in 0..words.size - length) {
                LANGUAGE_BY_TOKEN[words.subList(start, start + length).joinToString(" ")]?.let { return it }
            }
        }
    }
    return null
}

/**
 * Build the token table from the JDK's CLDR data: for each curated code, its two- and three-letter codes,
 * its bibliographic code, its English name and its own name for itself.
 *
 * A code the JDK cannot resolve to an ISO 639-2 form is skipped rather than half-registered, matching v1.
 * Registration order is load-bearing where two languages share a spelling: within a code the native name
 * is registered last, and across codes [LANGUAGE_CODES] order decides.
 */
private fun buildLanguageTokens(): Map<String, String> {
    val tokens = HashMap<String, String>()

    for (code in LANGUAGE_CODES) {
        val locale = Locale.of(code)
        val iso3 = try {
            locale.isO3Language
        } catch (_: MissingResourceException) {
            continue
        }
        // Matroska carries the bibliographic code where one exists, so that is what the report should
        // say the language is.
        val canonical = BIBLIOGRAPHIC[code] ?: iso3

        fun register(token: String?) {
            if (!token.isNullOrEmpty()) tokens[token.lowercase(Locale.ROOT)] = canonical
        }

        if (code !in AMBIGUOUS_CODES) register(code)
        register(iso3)
        register(BIBLIOGRAPHIC[code])
        register(locale.getDisplayLanguage(Locale.ENGLISH))
        register(locale.getDisplayLanguage(locale))
    }

    // Not an ISO code, but the abbreviation anime releases actually use.
    tokens["jap"] = "jpn"
    return tokens
}
