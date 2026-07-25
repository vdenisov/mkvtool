package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.EpisodeFetch
import org.plukh.mkvtool.core.FetchedEpisode
import org.plukh.mkvtool.core.ShowFetched
import org.plukh.mkvtool.core.TranslationFallback

/**
 * Pins the v1 text of every `fetch-episodes` line. All three go to stdout uncolored, as v1's bare
 * `println`s did — only the surrounding banner and the closing `*** Done` carry color, and both are
 * diagnostics rather than results.
 */
class EpisodeFetchRendererTest : FunSpec({

    test("the show line carries the name and the first-air year") {
        val (out, err) = renderResult(ShowFetchedRenderer, ShowFetched("Stub Show", 2006), colorEnabled = true)
        out shouldContain "*** The show is Stub Show (2006)"
        out shouldNotContain Char(27).toString()
        err.shouldBeEmpty()
    }

    test("a show with no known year says so rather than printing empty brackets") {
        renderResult(ShowFetchedRenderer, ShowFetched("Upcoming", null)).first shouldContain
            "*** The show is Upcoming (year unknown)"
    }

    test("the fill-in notice names the locale that came back untranslated") {
        renderResult(TranslationFallbackRenderer, TranslationFallback("ru-RU")).first shouldContain
            "*** Some names are untranslated in ru-RU; filling them from en-US"
    }

    test("the root reports the count and does not repeat the show line") {
        val (out, _) = renderResult(EpisodeFetchRenderer, fetch(episodes = 6))
        out shouldContain "*** Fetched 6 episode names"
        out shouldNotContain "The show is"
    }

    test("the count is not pluralized against its number, exactly as v1 writes it") {
        renderResult(EpisodeFetchRenderer, fetch(episodes = 1)).first shouldContain "*** Fetched 1 episode names"
    }
})

private fun fetch(episodes: Int) = EpisodeFetch(
    show = ShowFetched("Stub Show", 2006),
    showName = "Stub Show",
    season = 1,
    seasonName = "Season 1",
    language = null,
    filledFromEnglish = false,
    episodes = (1..episodes).map { FetchedEpisode(it, "Episode $it") },
    yamlFile = "episodes.yaml",
    textFile = "episodes.txt",
)
