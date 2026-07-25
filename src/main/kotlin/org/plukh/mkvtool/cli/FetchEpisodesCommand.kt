package org.plukh.mkvtool.cli

import org.plukh.mkvtool.core.ApiKeyResolution
import org.plukh.mkvtool.core.HttpTmdbFetcher
import org.plukh.mkvtool.core.ShowRef
import org.plukh.mkvtool.core.TmdbException
import org.plukh.mkvtool.core.fetchEpisodes
import org.plukh.mkvtool.core.mkvtoolHome
import org.plukh.mkvtool.core.resolveApiKey
import org.plukh.mkvtool.core.resolveShowRef
import org.plukh.mkvtool.out.Error
import org.plukh.mkvtool.out.Header
import org.plukh.mkvtool.out.Renderer
import org.plukh.mkvtool.out.Success
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable

/**
 * `mkvtool fetch-episodes` — write one season's episode names from TheMovieDB into `episodes.yaml` and
 * `episodes.txt`. A port of `src/fetch_episodes.groovy`.
 *
 * Everything that can be settled without the network is settled first — the API key, the show id, the
 * season — so a mistyped argument costs no request. Exit codes: 2 for anything wrong with the arguments
 * or the key, 3 for anything the API could not answer, else 0.
 */
@Command(
    name = "fetch-episodes",
    mixinStandardHelpOptions = true,
    description = ["Fetch episode names for one season from TheMovieDB into episodes.yaml and episodes.txt."],
)
class FetchEpisodesCommand : Callable<Int> {

    @Option(
        names = ["-a", "--api-key"],
        description = ["TheMovieDB API key. If one is not supplied, will try to read it from an 'apikey.txt' file"],
    )
    var apiKey: String? = null

    @Option(
        names = ["-i", "--show-id"],
        required = true,
        description = ["TheMovieDB show ID, or the show's URL (https://www.themoviedb.org/tv/1920-twin-peaks)"],
    )
    var showId: String = ""

    @Option(
        names = ["-s", "--season"],
        description = ["The season number. Optional when the --show-id URL names a season"],
    )
    var season: String? = null

    @Option(
        names = ["-l", "--language"],
        paramLabel = "LOCALE",
        description = [
            "TheMovieDB locale for names, e.g. ru-RU. Defaults to en-US. " +
                "Names untranslated in that locale fall back to en-US",
        ],
    )
    var language: String? = null

    // Test seam: lets the offline suite point the command at a local stub server instead of the real API.
    // Hidden because it is of no use in normal operation.
    @Option(names = ["--base-url"], hidden = true, description = ["Override the API base URL (for testing)"])
    var baseUrl: String = "https://api.themoviedb.org"

    @Mixin
    var output: OutputOptions = OutputOptions()

    override fun call(): Int {
        val renderer = output.renderer()

        val key = when (val resolution = resolveApiKey(apiKey, File("."), mkvtoolHome())) {
            is ApiKeyResolution.Found -> resolution.key
            is ApiKeyResolution.Problem -> return fail(renderer, resolution.message)
        }

        val ref = when (val resolution = resolveShowRef(showId, season)) {
            is ShowRef.Resolved -> resolution
            is ShowRef.Problem -> return fail(renderer, resolution.message)
        }

        renderer.render(Header("*** Fetching episodes from TheMovieDB..."))

        return try {
            fetchEpisodes(
                dir = File("."),
                fetcher = HttpTmdbFetcher(baseUrl, key),
                showId = ref.showId,
                season = ref.season,
                language = language,
                renderer = renderer,
            )
            renderer.render(Success("*** Done"))
            0
        } catch (e: TmdbException) {
            // Nothing is written before the whole season is in hand, so a failure here leaves no
            // half-written episodes.yaml behind.
            renderer.render(Error(e.message!!))
            3
        }
    }

    private fun fail(renderer: Renderer, message: String): Int {
        renderer.render(Error(message))
        return 2
    }
}
