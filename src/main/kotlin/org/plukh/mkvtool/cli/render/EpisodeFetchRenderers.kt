package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.EpisodeFetch
import org.plukh.mkvtool.core.ShowFetched
import org.plukh.mkvtool.core.TranslationFallback
import org.plukh.mkvtool.out.ResultTextRenderer

/**
 * The show, printed as soon as it is known — before the season fetch, so a slow link says something.
 * A show with no first-air date reads `(year unknown)` rather than an empty pair of brackets.
 */
val ShowFetchedRenderer = ResultTextRenderer<ShowFetched> { result, s ->
    s.out.println("*** The show is ${result.showName} (${result.year ?: "year unknown"})")
}

/** Said while the extra en-US requests are being made, so the pause is accounted for. */
val TranslationFallbackRenderer = ResultTextRenderer<TranslationFallback> { result, s ->
    s.out.println("*** Some names are untranslated in ${result.locale}; filling them from en-US")
}

/**
 * The count, and nothing else: the show line was already printed by [ShowFetchedRenderer] as its own
 * result, and repeating it here would say the same thing twice.
 *
 * "episode names" is not pluralized against the count — v1 writes it that way, and the text is what the
 * port reproduces.
 */
val EpisodeFetchRenderer = ResultTextRenderer<EpisodeFetch> { result, s ->
    s.out.println("*** Fetched ${result.episodes.size} episode names")
}
