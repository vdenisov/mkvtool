package org.plukh.mkvtool.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * `fetch-episodes` without a network: the fetcher seam takes canned bodies, so what is under test is the
 * argument resolution, the en-US fill-in rules, and what lands on disk.
 */
class FetchEpisodesTest : FunSpec({

    context("resolveShowRef") {
        data class Case(val name: String, val showId: String, val season: String?, val expected: ShowRef)

        withData(
            nameFn = { it.name },
            Case("a bare id with a season", "2260", "1", ShowRef.Resolved("2260", "1")),
            Case(
                "the show URL people actually paste",
                "https://www.themoviedb.org/tv/1920-twin-peaks",
                "1",
                ShowRef.Resolved("1920", "1"),
            ),
            Case(
                "a URL with no slug",
                "https://www.themoviedb.org/tv/1920",
                "2",
                ShowRef.Resolved("1920", "2"),
            ),
            Case(
                "a URL with a query string",
                "https://www.themoviedb.org/tv/1920-twin-peaks?language=ru",
                "1",
                ShowRef.Resolved("1920", "1"),
            ),
            Case(
                "a season URL supplies the season",
                "https://www.themoviedb.org/tv/1920-twin-peaks/season/2",
                null,
                ShowRef.Resolved("1920", "2"),
            ),
            Case(
                "an agreeing --season is not a conflict",
                "https://www.themoviedb.org/tv/1920/season/2",
                "2",
                ShowRef.Resolved("1920", "2"),
            ),
            // The season goes into the request path as written, so padding is preserved rather than
            // normalized — v1 built the URL from the string and only the YAML from the number.
            Case("a padded season stays as written", "1920", "07", ShowRef.Resolved("1920", "07")),
        ) { (_, showId, season, expected) ->
            resolveShowRef(showId, season) shouldBe expected
        }

        test("junk that is neither a number nor a URL is refused, and says so") {
            val problem = resolveShowRef("not-a-url", "1").shouldBeInstanceOf<ShowRef.Problem>()
            problem.message shouldContain "neither a number nor"
            problem.message shouldContain "not-a-url"
        }

        test("a season in both places that disagrees is a mistake worth stopping for") {
            // Silently preferring either one fetches a season the user did not ask for.
            val problem = resolveShowRef("https://www.themoviedb.org/tv/1920/season/2", "1")
                .shouldBeInstanceOf<ShowRef.Problem>()
            problem.message shouldContain "conflicts with"
        }

        test("string comparison, so 1 and 01 are a conflict rather than the same season") {
            resolveShowRef("https://www.themoviedb.org/tv/1920/season/2", "02")
                .shouldBeInstanceOf<ShowRef.Problem>()
        }

        test("no season anywhere says a season is needed") {
            resolveShowRef("1920", null).shouldBeInstanceOf<ShowRef.Problem>()
                .message shouldContain "No season"
        }

        test("a non-numeric season is refused") {
            resolveShowRef("1920", "one").shouldBeInstanceOf<ShowRef.Problem>()
                .message shouldContain "--season is not a number"
        }
    }

    context("resolveApiKey") {
        test("the flag wins outright, without touching the filesystem") {
            val dir = tempdir()
            File(dir, "apikey.txt").writeText("from-file")
            resolveApiKey("from-flag", dir, dir) shouldBe ApiKeyResolution.Found("from-flag")
        }

        test("an empty flag is no flag at all") {
            val dir = tempdir()
            File(dir, "apikey.txt").writeText("from-file\n")
            resolveApiKey("", dir, tempdir()) shouldBe ApiKeyResolution.Found("from-file")
        }

        test("the current directory takes precedence over the home directory") {
            val dir = tempdir()
            val home = tempdir()
            File(dir, "apikey.txt").writeText("near\n")
            File(home, "apikey.txt").writeText("home\n")
            resolveApiKey(null, dir, home) shouldBe ApiKeyResolution.Found("near")
        }

        test("the home directory is the fallback the script directory used to be") {
            val home = tempdir()
            File(home, "apikey.txt").writeText("home-key\n")
            resolveApiKey(null, tempdir(), home) shouldBe ApiKeyResolution.Found("home-key")
        }

        test("blank lines and surrounding space are skipped, not passed to the API") {
            val home = tempdir()
            File(home, "apikey.txt").writeText("\n   \n  the-key  \nsomething else\n")
            resolveApiKey(null, tempdir(), home) shouldBe ApiKeyResolution.Found("the-key")
        }

        test("no file anywhere names both places it looked") {
            val home = File(tempdir(), ".mkvtool")
            val problem = resolveApiKey(null, tempdir(), home).shouldBeInstanceOf<ApiKeyResolution.Problem>()
            problem.message shouldContain "No API key"
            problem.message shouldContain "current directory"
            problem.message shouldContain home.path
        }

        test("a file with nothing usable in it is reported as empty, by path") {
            val home = tempdir()
            val file = File(home, "apikey.txt").apply { writeText("\n  \n") }
            val problem = resolveApiKey(null, tempdir(), home).shouldBeInstanceOf<ApiKeyResolution.Problem>()
            problem.message shouldContain "API key file is empty"
            problem.message shouldContain file.absolutePath
        }
    }

    context("mkvtoolHome") {
        test("defaults to .mkvtool under the user's home") {
            mkvtoolHome(emptyMap(), "/home/vd") shouldBe File("/home/vd", ".mkvtool")
        }

        test("MKVTOOL_HOME overrides it, and an empty value does not") {
            mkvtoolHome(mapOf("MKVTOOL_HOME" to "/elsewhere"), "/home/vd") shouldBe File("/elsewhere")
            mkvtoolHome(mapOf("MKVTOOL_HOME" to ""), "/home/vd") shouldBe File("/home/vd", ".mkvtool")
        }
    }

    context("fetchEpisodes") {
        test("writes both files, with the names exactly as the API spells them") {
            val dir = tempdir()
            val fetcher = fakeFetcher(
                "/3/tv/2260" to """{"name":"Stub Show","first_air_date":"2006-07-07"}""",
                "/3/tv/2260/season/1" to """
                    {"name":"Season 1","episodes":[
                      {"episode_number":1,"name":"Plain Title"},
                      {"episode_number":2,"name":"Slash/Colon: Question?"}
                    ]}
                """.trimIndent(),
            )

            val result = fetchEpisodes(dir, fetcher, "2260", "1", null, SilentRenderer)

            result.showName shouldBe "Stub Show"
            result.show.year shouldBe 2006
            result.season shouldBe 1
            result.seasonName shouldBe "Season 1"
            result.language shouldBe null
            result.filledFromEnglish shouldBe false
            result.episodes shouldBe listOf(
                FetchedEpisode(1, "Plain Title"),
                FetchedEpisode(2, "Slash/Colon: Question?"),
            )

            // Nothing is sanitized here: the characters a file name cannot carry are precisely the ones
            // these files exist to preserve.
            File(dir, "episodes.txt").readLines(Charsets.UTF_8) shouldBe
                listOf("Plain Title", "Slash/Colon: Question?")

            val yaml = Yaml().load<Map<String, Any>>(File(dir, "episodes.yaml").readText(Charsets.UTF_8))
            yaml["show"] shouldBe "Stub Show"
            yaml["year"] shouldBe 2006
            yaml["season"] shouldBe 1
            yaml["seasonName"] shouldBe "Season 1"
            yaml.containsKey("language") shouldBe false

            @Suppress("UNCHECKED_CAST")
            val entries = yaml["episodes"] as List<Map<String, Any>>
            entries.map { it["episode"] } shouldBe listOf(1, 2)
            entries.map { it["name"] } shouldBe listOf("Plain Title", "Slash/Colon: Question?")
        }

        test("non-ASCII names survive the yaml, unescaped") {
            val dir = tempdir()
            val fetcher = fakeFetcher(
                "/3/tv/1920" to """{"name":"Твин Пикс","first_air_date":"1990-04-08"}""",
                "/3/tv/1920/season/1" to """{"name":"Сезон 1","episodes":[{"episode_number":1,"name":"Пилот"}]}""",
            )

            fetchEpisodes(dir, fetcher, "1920", "1", "ru-RU", SilentRenderer)

            val text = File(dir, "episodes.yaml").readText(Charsets.UTF_8)
            text shouldContain "Пилот"
            // allowUnicode: without it every Cyrillic character becomes an escape sequence.
            text shouldContain "Твин Пикс"
            File(dir, "episodes.txt").readLines(Charsets.UTF_8) shouldBe listOf("Пилот")
        }

        test("records the locale it fetched in, so a re-read knows what it is looking at") {
            val dir = tempdir()
            val fetcher = fakeFetcher(
                "/3/tv/1920" to """{"name":"Твин Пикс"}""",
                "/3/tv/1920/season/1" to """{"name":"Сезон 1","episodes":[{"episode_number":1,"name":"Пилот"}]}""",
            )

            fetchEpisodes(dir, fetcher, "1920", "1", "ru-RU", SilentRenderer)

            val yaml = Yaml().load<Map<String, Any>>(File(dir, "episodes.yaml").readText(Charsets.UTF_8))
            yaml["language"] shouldBe "ru-RU"
            // No first_air_date, so no year key at all rather than a null one.
            yaml.containsKey("year") shouldBe false
        }

        test("an untranslated name is filled from en-US, and only that name") {
            val dir = tempdir()
            val asked = mutableListOf<Pair<String, String>>()
            val fetcher = TmdbFetcher { path, locale ->
                asked += path to locale
                when {
                    path == "/3/tv/1920" && locale == "ru-RU" -> """{"name":"Твин Пикс"}"""
                    path == "/3/tv/1920" -> """{"name":"Twin Peaks"}"""
                    locale == "ru-RU" -> """
                        {"name":"Сезон 1","episodes":[
                          {"episode_number":1,"name":"Пилот"},{"episode_number":2,"name":""}
                        ]}
                    """.trimIndent()
                    else -> """
                        {"name":"Season 1","episodes":[
                          {"episode_number":1,"name":"Pilot"},{"episode_number":2,"name":"Traces to Nowhere"}
                        ]}
                    """.trimIndent()
                }
            }

            val result = fetchEpisodes(dir, fetcher, "1920", "1", "ru-RU", SilentRenderer)

            result.filledFromEnglish shouldBe true
            result.episodes shouldBe listOf(
                FetchedEpisode(1, "Пилот"),
                FetchedEpisode(2, "Traces to Nowhere"),
            )
            // The translated season name stood, so it was not overwritten by the fill-in fetch.
            result.seasonName shouldBe "Сезон 1"
            asked.map { it.second } shouldBe listOf("ru-RU", "ru-RU", "en-US", "en-US")
        }

        test("no fill-in without --language: en-US is already the fallback") {
            val dir = tempdir()
            val asked = mutableListOf<String>()
            val fetcher = TmdbFetcher { path, locale ->
                asked += locale
                if (path.contains("season")) """{"episodes":[{"episode_number":1,"name":""}]}"""
                else """{"name":"Stub"}"""
            }

            val result = fetchEpisodes(dir, fetcher, "1", "1", null, SilentRenderer)

            result.filledFromEnglish shouldBe false
            result.episodes shouldBe listOf(FetchedEpisode(1, ""))
            asked shouldBe listOf("en-US", "en-US")
        }

        test("an unnumbered episode falls back on its position rather than writing no number") {
            val dir = tempdir()
            val fetcher = fakeFetcher(
                "/3/tv/1" to """{"name":"Stub"}""",
                "/3/tv/1/season/1" to """{"episodes":[{"name":"First"},{"name":"Second"}]}""",
            )

            fetchEpisodes(dir, fetcher, "1", "1", null, SilentRenderer).episodes shouldBe
                listOf(FetchedEpisode(1, "First"), FetchedEpisode(2, "Second"))
        }

        test("real episode numbers are kept, so a gap does not shift the season") {
            val dir = tempdir()
            val fetcher = fakeFetcher(
                "/3/tv/1" to """{"name":"Stub"}""",
                "/3/tv/1/season/1" to
                    """{"episodes":[{"episode_number":11,"name":"Eleven"},{"episode_number":13,"name":"Thirteen"}]}""",
            )

            fetchEpisodes(dir, fetcher, "1", "1", null, SilentRenderer).episodes shouldBe
                listOf(FetchedEpisode(11, "Eleven"), FetchedEpisode(13, "Thirteen"))
        }

        test("a season with no episodes is an error, not an empty pair of files") {
            val dir = tempdir()
            val fetcher = fakeFetcher(
                "/3/tv/1" to """{"name":"Stub"}""",
                "/3/tv/1/season/9" to """{"episodes":[]}""",
            )

            shouldThrow<TmdbException> { fetchEpisodes(dir, fetcher, "1", "9", null, SilentRenderer) }
                .message shouldContain "No episodes returned for season 9"
            File(dir, "episodes.txt").exists() shouldBe false
            File(dir, "episodes.yaml").exists() shouldBe false
        }

        test("a body that is not the document it should be names the path it came from") {
            val dir = tempdir()
            shouldThrow<TmdbException> {
                fetchEpisodes(dir, { _, _ -> "<html>nope</html>" }, "1", "1", null, SilentRenderer)
            }.message shouldContain "non-JSON response for /3/tv/1"
        }

        test("an unaired show has no year rather than a broken date") {
            val dir = tempdir()
            val fetcher = fakeFetcher(
                "/3/tv/1" to """{"name":"Upcoming","first_air_date":""}""",
                "/3/tv/1/season/1" to """{"episodes":[{"episode_number":1,"name":"One"}]}""",
            )

            fetchEpisodes(dir, fetcher, "1", "1", null, SilentRenderer).show.year shouldBe null
        }
    }
})

/** A fetcher answering canned bodies by path, whatever locale is asked for. */
private fun fakeFetcher(vararg routes: Pair<String, String>): TmdbFetcher {
    val byPath = routes.toMap()
    return TmdbFetcher { path, _ -> byPath[path] ?: error("no canned body for $path") }
}
