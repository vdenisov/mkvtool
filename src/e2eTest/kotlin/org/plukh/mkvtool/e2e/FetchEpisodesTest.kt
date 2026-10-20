package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.Enabled
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.plukh.mkvtool.core.EpisodeData
import org.plukh.mkvtool.core.MappingLoad
import org.plukh.mkvtool.core.loadMapping
import org.plukh.mkvtool.core.normalizeYaml
import org.plukh.mkvtool.core.strictDecode
import org.plukh.mkvtool.e2e.support.Live
import org.plukh.mkvtool.e2e.support.Smoke
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.withStubServer
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * `fetch-episodes` writes `episodes.yaml` and `episodes.txt` from TheMovieDB, raw and unsanitized.
 *
 * Every case runs against [withStubServer] through the hidden `--base-url`, offline and deterministic, except
 * the one tagged [Live], which asks the real API whether it has changed shape.
 */
class FetchEpisodesTest : FunSpec({

    test("both files carry TheMovieDB's spelling, nothing stripped").config(tags = setOf(Smoke)) {
        val workDir = tempdir()
        // Every character a file name cannot hold. Stripping them is rename's job, at the point a name becomes
        // a file name; mux needs the real ':' and '?' for a title.
        val rawTitles = listOf(
            "Plain Title",
            "Slash/Colon: Question?",
            "Quote\"Star*Pipe|",
            "Back\\slash<gt>",
            "Trailing dots...",
            "Trailing space   ",
        )
        val routes = tmdbRoutes(2260, "Stub Show", "2006-07-07", 1, rawTitles, seasonName = "Season 1")

        val run = withStubServer(routes) { baseUrl ->
            mkvtool("fetch-episodes", *stubArgs(baseUrl), "--show-id", "2260", "--season", "1", workDir = workDir)
        }

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "Stub Show"
            run.output shouldContain "2006"
        }
        File(workDir, "episodes.txt").readLines(StandardCharsets.UTF_8) shouldBe rawTitles

        val yaml = loadEpisodesYaml(workDir)
        yaml.show shouldBe "Stub Show"
        yaml.year shouldBe "2006"
        yaml.season shouldBe "01"
        yaml.seasonName shouldBe "Season 1"
        yaml.byEpisode.keys.toList() shouldBe listOf("01", "02", "03", "04", "05", "06")
        yaml.byEpisode.values.toList() shouldBe rawTitles
    }

    test("a failing request reports the status and writes nothing") {
        val workDir = tempdir()

        val run = withStubServer(emptyMap()) { baseUrl ->
            mkvtool("fetch-episodes", *stubArgs(baseUrl), "--show-id", "2260", "--season", "1", workDir = workDir)
        }

        withClue(run.output) {
            run.exitCode shouldNotBe 0
            run.output shouldContain "404"
            run.output shouldNotContain "Exception"
        }
        File(workDir, "episodes.txt").exists() shouldBe false
        File(workDir, "episodes.yaml").exists() shouldBe false
    }

    context("--show-id accepts a TheMovieDB URL") {
        withData(
            "https://www.themoviedb.org/tv/1920-twin-peaks",
            "https://www.themoviedb.org/tv/1920",
            "https://www.themoviedb.org/tv/1920-twin-peaks?language=ru",
        ) { url ->
            val workDir = tempdir()
            val routes = tmdbRoutes(1920, "Twin Peaks", "1990-04-08", 1, listOf("Pilot"))

            val run = withStubServer(routes) { baseUrl ->
                mkvtool("fetch-episodes", *stubArgs(baseUrl), "--show-id", url, "--season", "1", workDir = workDir)
            }

            withClue(run.output) {
                run.exitCode shouldBe 0
                run.output shouldContain "Twin Peaks"
            }
        }
    }

    test("the season can come from the URL") {
        val workDir = tempdir()
        val routes = tmdbRoutes(1920, "Twin Peaks", "1990-04-08", 2, listOf("May the Giant"))

        val run = withStubServer(routes) { baseUrl ->
            mkvtool(
                "fetch-episodes", *stubArgs(baseUrl),
                "--show-id", "https://www.themoviedb.org/tv/1920-twin-peaks/season/2",
                workDir = workDir,
            )
        }

        withClue(run.output) { run.exitCode shouldBe 0 }
        File(workDir, "episodes.txt").readLines(StandardCharsets.UTF_8) shouldBe listOf("May the Giant")
    }

    context("unusable show and season arguments exit 2 and say why") {
        withData(
            nameFn = { it.label },
            RefusalRow(
                "a show id that is neither a number nor a URL",
                listOf("--show-id", "not-a-url", "--season", "1"),
                "neither a number nor",
            ),
            // An explicit season disagreeing with the URL's is a mistake worth stopping for.
            RefusalRow(
                "a --season conflicting with the URL's",
                listOf("--season", "1", "--show-id", "https://www.themoviedb.org/tv/1920/season/2"),
                "conflicts with",
            ),
            RefusalRow("no season from either source", listOf("--show-id", "1920"), "No season"),
        ) { row ->
            val workDir = tempdir()
            val routes = tmdbRoutes(1920, "Twin Peaks", "1990-04-08", 2, listOf("May the Giant"))

            val run = withStubServer(routes) { baseUrl ->
                mkvtool("fetch-episodes", *stubArgs(baseUrl), *row.args.toTypedArray(), workDir = workDir)
            }

            withClue(run.output) {
                run.exitCode shouldBe 2
                run.output shouldContain row.message
            }
        }
    }

    test("--language records the locale and fills untranslated names from en-US") {
        val workDir = tempdir()
        // TheMovieDB answers an untranslated name with an empty string rather than falling back on its own.
        val routes = tmdbRoutes(1920, "Твин Пикс", "1990-04-08", 1, listOf("Пилот", ""), seasonName = "Сезон 1")

        val run = withStubServer(routes) { baseUrl ->
            mkvtool(
                "fetch-episodes", *stubArgs(baseUrl), "--show-id", "1920", "--season", "1", "--language", "ru-RU",
                workDir = workDir,
            )
        }

        withClue(run.output) {
            run.exitCode shouldBe 0
            // The stub ignores the query string, so the en-US top-up gets the same empty name back. What is
            // under test is that the run noticed and said so rather than writing a blank silently.
            run.output shouldContain "untranslated"
        }
        val yaml = loadEpisodesYaml(workDir)
        yaml.language shouldBe "ru-RU"
        yaml.show shouldBe "Твин Пикс"
        yaml.byEpisode["01"] shouldBe "Пилот"
    }

    test("non-ASCII titles survive fetch, episodes.txt and rename").config(tags = setOf(Smoke)) {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e01.mkv")
        stageInput(workDir, "Show.s01e02.mkv")
        val titles = listOf("Волчица и пряности", "Тест: второй эпизод")
        val routes = tmdbRoutes(2260, "Спайс и Волк", "2008-01-09", 1, titles)

        val fetch = withStubServer(routes) { baseUrl ->
            mkvtool("fetch-episodes", *stubArgs(baseUrl), "--show-id", "2260", "--season", "1", workDir = workDir)
        }
        withClue(fetch.output) { fetch.exitCode shouldBe 0 }

        // Strict, so bytes that are not UTF-8 fail here rather than decoding into replacement characters that
        // the comparison below might never be shown.
        val decoded = strictDecode(File(workDir, "episodes.txt").readBytes(), StandardCharsets.UTF_8)
        // Raw: the colon is stripped where the name becomes a file name, below.
        // Only the trailing line break is dropped: a blank line between titles would shift every title after it.
        decoded.lines().dropLastWhile { it.isEmpty() } shouldBe titles

        // The show name stays ASCII on purpose: a Cyrillic argument would test subprocess argument encoding
        // instead, and a failure there would look identical to the regression this case exists to catch.
        val rename = mkvtool("rename", "My Show", workDir = workDir)
        withClue(rename.output) { rename.exitCode shouldBe 0 }

        val names = workDir.list().orEmpty().toList()
        names shouldContain "My Show - S01E01 - Волчица и пряности.mkv"
        names shouldContain "My Show - S01E02 - Тест второй эпизод.mkv"
        names.filter { '?' in it || '�' in it } shouldBe emptyList()
    }

    // Not "does our code work" but "has the API changed shape" - which no commit causes, so no ordinary run
    // selects it: the live-contract job does, on its schedule and on dispatch, and a developer can with
    // -Pe2eTags=Live. Assertions are loose on purpose: TheMovieDB may legitimately edit episode titles, and
    // pinning them would produce failures that are not bugs.
    test("the live API still has the shape we parse").config(
        tags = setOf(Live),
        enabledOrReasonIf = {
            if (System.getenv("TMDB_API_KEY").isNullOrBlank()) {
                Enabled.disabled("no TheMovieDB API key in TMDB_API_KEY")
            } else {
                Enabled.enabled
            }
        },
    ) {
        val workDir = tempdir()

        // Show 2260 is "H2O: Just Add Water", a finished series, so its season 1 is not going to move.
        val run = mkvtool(
            "fetch-episodes", "--api-key", System.getenv("TMDB_API_KEY"), "--show-id", "2260", "--season", "1",
            workDir = workDir,
        )

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "H2O"
        }
        val lines = File(workDir, "episodes.txt").readLines(StandardCharsets.UTF_8)
        lines.count { it.isNotBlank() } shouldBeGreaterThanOrEqual 20
    }
})

/** One `fetch-episodes` invocation refused before anything is fetched. */
private class RefusalRow(val label: String, val args: List<String>, val message: String)

private fun stubArgs(baseUrl: String): Array<String> = arrayOf("--api-key", "stub", "--base-url", baseUrl)

/**
 * The two TheMovieDB endpoints `fetch-episodes` calls, for one show and one season. Serialised rather than
 * string-built: the titles under test carry quotes and backslashes on purpose.
 */
private fun tmdbRoutes(
    showId: Int,
    showName: String,
    firstAirDate: String,
    season: Int,
    episodes: List<String>,
    seasonName: String? = null,
): Map<String, String> {
    val show = buildJsonObject {
        put("name", showName)
        put("first_air_date", firstAirDate)
    }
    val seasonJson = buildJsonObject {
        seasonName?.let { put("name", it) }
        putJsonArray("episodes") {
            episodes.forEachIndexed { i, name ->
                addJsonObject {
                    put("episode_number", i + 1)
                    put("name", name)
                }
            }
        }
    }
    return mapOf("/3/tv/$showId" to show.toString(), "/3/tv/$showId/season/$season" to seasonJson.toString())
}

/** `episodes.yaml` read through the production loader, under its fixed UTF-8 contract. */
private fun loadEpisodesYaml(workDir: File): EpisodeData {
    val loaded = loadMapping(File(workDir, "episodes.yaml"), StandardCharsets.UTF_8, ::normalizeYaml)
    check(loaded is MappingLoad.Loaded) { "episodes.yaml did not load: $loaded" }
    return loaded.value
}
