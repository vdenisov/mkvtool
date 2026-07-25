package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe

/**
 * The CLDR-derived language guesser.
 *
 * Every expectation was taken from the v1 engine rather than from a reading of it: `guessLanguage` was
 * run over every spelling of all 36 curated codes — two-letter, three-letter, bibliographic, English name
 * and native name, alone and inside a realistic folder name — plus the adversarial tokens below, and this
 * port reproduced all 295 answers. What is kept here is the subset that says *why* each rule exists; the
 * exhaustive sweep was a one-off differential, not something worth freezing as 295 cases.
 *
 * The positives and true negatives from harness case `110_discovery_engine_matching` are all present.
 */
class LanguageGuessTest : FunSpec({

    data class Case(val label: String, val texts: List<String?>, val expected: String?)

    context("whole-word matching") {
        withData(
            nameFn = { it.label },
            Case("a three-letter code in a directory name", listOf("Rus sound"), "rus"),
            Case("a two-letter code as a word", listOf("Ru subs"), "rus"),
            Case("punctuation separates words", listOf("Ru.subs"), "rus"),
            // Without this rule a short code would fire on any release group or show title that
            // happened to contain the letters.
            Case("no match inside a longer word", listOf("Rusubs"), null),
            Case("a bare code on its own", listOf("ja"), "jpn"),
            Case("matching ignores case", listOf("RUS SOUND"), "rus"),
            // Digits count as word characters, not separators, so they do not split a token open.
            Case("a digit does not separate words", listOf("Rus2sound"), null),
            Case("an episode token says nothing", listOf("S01E01"), null),
            Case("nor does an encoding string", listOf("x264 1080p"), null),
        ) { guessLanguage(it.texts) shouldBe it.expected }
    }

    context("spellings come from CLDR") {
        withData(
            nameFn = { it.label },
            Case("a native name in its own script", listOf("Русский"), "rus"),
            Case("a native name in a non-Latin script", listOf("日本語"), "jpn"),
            Case("a native name with diacritics", listOf("Español"), "spa"),
            Case("a native Cyrillic name other than Russian", listOf("Українська"), "ukr"),
            Case("an English name", listOf("English subs"), "eng"),
            Case("an English name of a language written elsewhere", listOf("Chinese Simplified subs"), "chi"),
            Case("a German native name", listOf("Deutsch"), "ger"),
            // The only multi-word name in the curated set, and therefore the one case that exercises
            // matching runs of words after every single word has been tried.
            Case("a two-word native name matches as a run", listOf("Tiếng Việt subs"), "vie"),
            Case("neither of its words matches alone", listOf("Tiếng"), null),
        ) { guessLanguage(it.texts) shouldBe it.expected }
    }

    context("bibliographic codes") {
        withData(
            nameFn = { it.label },
            // Matroska carries the /B form where one exists, so that is what a match reports itself as,
            // whichever spelling found it.
            Case("Greek reports its bibliographic code", listOf("Greek subs"), "gre"),
            Case("and answers to it directly", listOf("gre"), "gre"),
            Case("German likewise", listOf("Deutsch"), "ger"),
            Case("Chinese likewise", listOf("zh"), "chi"),
            // Dropped from the table because /B and /T agree on it, so the fallback to getISO3Language
            // has to produce the same answer the table used to.
            Case("Serbian has no separate /B code", listOf("Serbian"), "srp"),
            // Not an ISO code at all, but the abbreviation anime releases actually use.
            Case("the anime abbreviation for Japanese", listOf("jap"), "jpn"),
        ) { guessLanguage(it.texts) shouldBe it.expected }
    }

    context("codes that are ordinary words are withheld") {
        withData(
            nameFn = { it.label },
            Case("'No' is not Norwegian", listOf("No subs"), null),
            Case("'to' is not a language here", listOf("Extras (to be done)"), null),
            Case("'UK' is a region, not Ukrainian", listOf("UK BluRay"), null),
            Case("'El' is a Spanish article, not Greek", listOf("El Bosque"), null),
            Case("'It' is English", listOf("It Follows"), null),
            Case("'He' is English", listOf("He Man"), null),
            Case("'Hi' is English", listOf("Hi Score Girl"), null),
            Case("'Da' is a preposition", listOf("Da Vinci"), null),
            Case("'Et' is a conjunction", listOf("Et cetera"), null),
            // Only the bare two-letter form is withheld, which is what makes an entry cheap: the
            // language still answers to its three-letter code and to both its names.
            Case("but Ukrainian's three-letter code still matches", listOf("Ukr sound"), "ukr"),
            Case("and its native name", listOf("Українська"), "ukr"),
            Case("and Norwegian's native name", listOf("norsk bokmal"), "nor"),
        ) { guessLanguage(it.texts) shouldBe it.expected }
    }

    context("the citation-form limit") {
        // CLDR supplies the citation form only — masculine nominative singular — so a folder whose
        // adjective agrees with its noun does not match. Both of these are Russian and both miss. The
        // result is no guess rather than a wrong one, and that is the intended failure: stem matching
        // would catch them and would reintroduce the substring false positives above.
        withData(
            nameFn = { it.label },
            Case("a feminine agreement misses", listOf("Русская озвучка"), null),
            Case("a plural agreement misses", listOf("Русские субтитры"), null),
            Case("the citation form itself matches", listOf("Русский"), "rus"),
        ) { guessLanguage(it.texts) shouldBe it.expected }
    }

    context("texts are tried in order, so the caller sets precedence") {
        withData(
            nameFn = { it.label },
            // A suffix or the file's own directory describes it better than a category directory
            // three levels up, which is the order the variant hands them over in.
            Case("the first text that says anything wins", listOf("[Omicron]", "Rus subs"), "rus"),
            Case("a null text is skipped", listOf(null, "Rus sound"), "rus"),
            Case("an empty text is skipped", listOf("", "Deutsch"), "ger"),
            // Diacritics are part of the spelling: the unaccented form is not in the table, so the
            // first text misses and the second answers.
            Case("an unaccented spelling misses and the next text answers", listOf("Espanol subs", "Español"), "spa"),
            Case("nothing anywhere yields no guess", listOf("nothing here", "also nothing"), null),
            Case("no texts at all yields no guess", emptyList(), null),
        ) { guessLanguage(it.texts) shouldBe it.expected }
    }
})
