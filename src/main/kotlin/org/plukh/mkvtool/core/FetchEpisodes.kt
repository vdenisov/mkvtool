package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.Renderer
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.LocalDate

/**
 * `fetch-episodes`: one season's episode names from TheMovieDB into `episodes.yaml` and `episodes.txt`.
 *
 *
 * **Nothing is sanitized here.** Names are written exactly as TheMovieDB spells them, `:` and `?`
 * included: `rename` strips what a file name cannot carry at the point a name becomes one, and `mux`
 * needs the original spelling for `${'$'}{episodeName}`. Stripped here, it could never be recovered.
 */

/** Where the personal API key lives when it is not passed per invocation. */
fun mkvtoolHome(
    env: Map<String, String> = System.getenv(),
    userHome: String = System.getProperty("user.home"),
): File = env["MKVTOOL_HOME"]?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: File(userHome, ".mkvtool")

/** The key, or why there is none. */
sealed interface ApiKeyResolution {
    data class Found(val key: String) : ApiKeyResolution

    /** [message] is the sentence the command prints before exiting 2. */
    data class Problem(val message: String) : ApiKeyResolution
}

/**
 * The API key: the flag, then `apikey.txt` in [dir], then in [home].
 *
 * The current directory keeps its v1 precedence; what changed is the fallback behind it — v1 looked next
 * to the script, which a single binary on `PATH` has no equivalent of, so the key now lives in the tool's
 * home directory instead. It is a personal secret the user places, which is why nothing creates it.
 */
fun resolveApiKey(explicit: String?, dir: File, home: File): ApiKeyResolution {
    if (!explicit.isNullOrEmpty()) return ApiKeyResolution.Found(explicit)

    val keyFile = listOf(File(dir, "apikey.txt"), File(home, "apikey.txt")).firstOrNull { it.exists() }
        ?: return ApiKeyResolution.Problem(
            "No API key: pass --api-key, or create apikey.txt in the current directory or in ${home.path}"
        )

    val key = readLinesDetected(keyFile).firstOrNull { it.isNotBlank() }?.trim()
    if (key.isNullOrEmpty()) return ApiKeyResolution.Problem("API key file is empty: ${keyFile.absolutePath}")
    return ApiKeyResolution.Found(key)
}

/** Which show and season to fetch, or why the arguments do not say. */
sealed interface ShowRef {
    /** [season] stays a string: it goes into the request path as the user wrote it. */
    data class Resolved(val showId: String, val season: String) : ShowRef

    data class Problem(val message: String) : ShowRef
}

private val ALL_DIGITS = Regex("""\d+""")
private val ID_IN_URL = Regex("""/tv/(\d+)""")
private val SEASON_IN_URL = Regex("""/season/(\d+)""")

/**
 * Resolve `--show-id` and `--season`.
 *
 * `--show-id` doubles as a URL because the show's address is what people have to hand — picking the
 * numeric id out of it by eye is the step this saves. A season in the URL supplies `--season` when that
 * was not passed, so a pasted season address needs nothing else; a season in *both* that disagree is
 * always a mistake worth stopping for, since silently preferring either fetches a season nobody asked
 * for.
 */
fun resolveShowRef(showId: String, season: String?): ShowRef {
    var id = showId
    var wanted = season

    if (!ALL_DIGITS.matches(id)) {
        val url = id
        val idMatch = ID_IN_URL.find(url)
            ?: return ShowRef.Problem("--show-id is neither a number nor a TheMovieDB show URL: $url")
        id = idMatch.groupValues[1]

        val urlSeason = SEASON_IN_URL.find(url)?.groupValues?.get(1)
        if (urlSeason != null) {
            if (wanted != null && wanted != urlSeason) {
                return ShowRef.Problem("--season $wanted conflicts with season $urlSeason in the --show-id URL")
            }
            wanted = urlSeason
        }
    }

    if (wanted == null) return ShowRef.Problem("No season: pass --season, or a --show-id URL that names one")
    if (!ALL_DIGITS.matches(wanted)) return ShowRef.Problem("--season is not a number: $wanted")
    return ShowRef.Resolved(id, wanted)
}

/** The show, as soon as it is known — reported before the season fetch, which is the slow half. */
data class ShowFetched(val showName: String?, val year: Int?) : CommandResult

/**
 * Some names came back untranslated and are being filled from en-US. Its own result because it is said
 * while the extra requests are being made, not after them.
 */
data class TranslationFallback(val locale: String) : CommandResult

/** One episode as TheMovieDB spells it, by its real [number]. */
data class FetchedEpisode(val number: Int, val name: String)

/**
 * One season fetched and written.
 *
 * [showName] and [seasonName] are the names as written — after any en-US fill — while [show] carries what
 * the requested locale answered with, which is what was reported live. [episodes] carries **real**
 * episode numbers, which is what lets a season with a gap in it be read back without shifting everything
 * after the gap.
 */
data class EpisodeFetch(
    val show: ShowFetched,
    val showName: String,
    val season: Int,
    val seasonName: String,
    val language: String?,
    val filledFromEnglish: Boolean,
    val episodes: List<FetchedEpisode>,
    val yamlFile: String,
    val textFile: String,
) : CommandResult

/**
 * Fetch one season and write both files into [dir], emitting each result as it completes.
 *
 * Both files are written **explicit UTF-8**: they are handed to `rename` and `mux`, which read them back,
 * so the contract cannot depend on the ambient platform default.
 *
 * Throws [TmdbException] for anything the API could not answer — the caller turns it into exit 3.
 */
fun fetchEpisodes(
    dir: File,
    fetcher: TmdbFetcher,
    showId: String,
    season: String,
    language: String?,
    renderer: Renderer,
): EpisodeFetch {
    val locale = language?.takeIf { it.isNotEmpty() } ?: "en-US"
    val showPath = "/3/tv/$showId"
    val seasonPath = "/3/tv/$showId/season/$season"

    val show = parseTmdb<ShowDto>(fetcher.get(showPath, locale), showPath)
    // first_air_date is absent or empty for an unaired show, so it must not be assumed to parse.
    val airYear = show.firstAirDate?.takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it).year }
    renderer.render(ShowFetched(show.name, airYear))

    val seasonData = parseTmdb<SeasonDto>(fetcher.get(seasonPath, locale), seasonPath)
    if (seasonData.episodes.isEmpty()) {
        throw TmdbException("No episodes returned for season $season - check the season number")
    }

    // TheMovieDB answers an untranslated field with an empty string rather than falling back on its own,
    // so a partially translated season would otherwise be written with blank names. Re-fetch in en-US
    // only when something actually came back empty, and fill just the gaps.
    val needsFallback = !language.isNullOrEmpty() && (
        seasonData.episodes.any { it.name.isNullOrEmpty() } ||
            show.name.isNullOrEmpty() ||
            seasonData.name.isNullOrEmpty()
        )
    var fallbackShow: ShowDto? = null
    var fallbackSeason: SeasonDto? = null
    if (needsFallback) {
        renderer.render(TranslationFallback(locale))
        fallbackShow = parseTmdb<ShowDto>(fetcher.get(showPath), showPath)
        fallbackSeason = parseTmdb<SeasonDto>(fetcher.get(seasonPath), seasonPath)
    }

    val episodes = seasonData.episodes.mapIndexed { index, episode ->
        // A real response always numbers its episodes; falling back on the position keeps a truncated
        // payload from writing an episodes.yaml with no usable numbers in it at all.
        val number = episode.episodeNumber?.takeIf { it != 0 } ?: (index + 1)
        val fallbackName = fallbackSeason?.episodes?.firstOrNull { it.episodeNumber == number }?.name
        FetchedEpisode(number, episode.name.orEmpty().ifEmpty { fallbackName.orEmpty() })
    }

    val showName = show.name.orEmpty().ifEmpty { fallbackShow?.name.orEmpty() }
    val seasonName = seasonData.name.orEmpty().ifEmpty { fallbackSeason?.name.orEmpty() }

    val yamlFile = File(dir, "episodes.yaml")
    val textFile = File(dir, "episodes.txt")
    writeEpisodesYaml(yamlFile, showName, airYear, season, seasonName, language, episodes)
    textFile.writeText(
        episodes.joinToString("") { it.name + System.lineSeparator() },
        Charsets.UTF_8,
    )

    val result = EpisodeFetch(
        show = ShowFetched(show.name, airYear),
        showName = showName,
        season = season.toInt(),
        seasonName = seasonName,
        language = language?.takeIf { it.isNotEmpty() },
        filledFromEnglish = needsFallback,
        episodes = episodes,
        yamlFile = yamlFile.name,
        textFile = textFile.name,
    )
    renderer.render(result)
    return result
}

/**
 * The hand-editable metadata file, in the key order a reader expects: what it is, then its episodes.
 *
 * `allowUnicode` is not optional — without it snakeyaml rewrites every non-ASCII character as a numeric
 * escape, which is valid YAML and unreadable for exactly the titles this feature exists to preserve.
 */
internal fun writeEpisodesYaml(
    file: File,
    showName: String,
    airYear: Int?,
    season: String,
    seasonName: String,
    language: String?,
    episodes: List<FetchedEpisode>,
) {
    val data = LinkedHashMap<String, Any>()
    data["show"] = showName
    if (airYear != null) data["year"] = airYear
    data["season"] = season.toInt()
    if (seasonName.isNotEmpty()) data["seasonName"] = seasonName
    if (!language.isNullOrEmpty()) data["language"] = language
    data["episodes"] = episodes.map { linkedMapOf<String, Any>("episode" to it.number, "name" to it.name) }

    val options = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        isAllowUnicode = true
    }
    file.writeText(Yaml(options).dump(data), Charsets.UTF_8)
}
