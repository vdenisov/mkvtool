package org.plukh.mkvtool.e2e.support

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.plukh.mkvtool.core.MappingLoad
import org.plukh.mkvtool.core.loadMapping
import org.plukh.mkvtool.core.normalizeYaml
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The episode-metadata writers, read back through the production loader.
 *
 * `episodes.yaml` is asserted the way the config builder is: through the code that will actually read it,
 * not against an expected string. A writer emitting plausible YAML the loader parses differently would
 * pass a text comparison and break every case resting on it - and this file's whole reason for
 * serialising rather than string-building is that the titles under test are chosen to be hostile
 * (colons, question marks, quotes, Cyrillic).
 *
 * Needs no mkvmerge and no binary: text and bytes in, model out.
 */
class EpisodeFixturesTest : FunSpec({

    fun loadYaml(file: File) = loadMapping(file, transform = ::normalizeYaml)

    context("episodes.txt") {
        test("one title per line, with a trailing newline") {
            val workDir = tempdir()
            val file = writeEpisodes(workDir, listOf("First", "Second", "Third"))

            file.name shouldBe "episodes.txt"
            file.readText(StandardCharsets.UTF_8) shouldBe "First\nSecond\nThird\n"
        }

        test("written as UTF-8 whatever the platform default is") {
            val workDir = tempdir()
            val file = writeEpisodes(workDir, listOf("Пилот"))

            // Asserted on the bytes rather than on a re-read, which would agree with itself whatever
            // charset was used.
            file.readBytes() shouldBe "Пилот\n".toByteArray(StandardCharsets.UTF_8)
        }
    }

    context("episodes.yaml") {
        test("round-trips through the production loader") {
            val workDir = tempdir()
            val file = writeEpisodesYaml(
                workDir,
                show = "My Show",
                year = 2011,
                season = 2,
                seasonName = "Season Two",
                language = "en-US",
                episodes = mapOf(1 to "First", 2 to "Second"),
            )

            val loaded = loadYaml(file)
            check(loaded is MappingLoad.Loaded) { "the writer emitted YAML the loader rejected: $loaded" }

            loaded.value.show shouldBe "My Show"
            loaded.value.year shouldBe "2011"
            // The loader pads numbers into keys, so a number written here comes back as "02"/"01".
            loaded.value.season shouldBe "02"
            loaded.value.seasonName shouldBe "Season Two"
            loaded.value.byEpisode["01"] shouldBe "First"
            loaded.value.byEpisode["02"] shouldBe "Second"
        }

        test("real episode numbers survive a gap in the season") {
            val workDir = tempdir()
            // The reason the yaml exists at all: a gap must not shift everything after it, which is what
            // line-order matching in episodes.txt would do.
            val file = writeEpisodesYaml(workDir, episodes = mapOf(1 to "First", 5 to "Fifth"))

            val loaded = loadYaml(file) as MappingLoad.Loaded
            loaded.value.byEpisode.keys shouldBe setOf("01", "05")
            loaded.value.byEpisode["05"] shouldBe "Fifth"
        }

        test("a title carrying colons and question marks survives unsanitized") {
            val workDir = tempdir()
            // Nothing is stripped at fetch time - that happens in rename, at the point a name becomes a
            // file name - so a writer that quoted this wrongly would be testing the wrong thing.
            val file = writeEpisodesYaml(workDir, episodes = mapOf(1 to "Slash/Colon: Question?"))

            val loaded = loadYaml(file) as MappingLoad.Loaded
            loaded.value.byEpisode["01"] shouldBe "Slash/Colon: Question?"
        }

        test("Cyrillic is written literally rather than escaped") {
            val workDir = tempdir()
            val file = writeEpisodesYaml(workDir, show = "Шоу", episodes = mapOf(1 to "Начало"))

            // allowUnicode is what makes this hold. Without it snakeyaml emits numeric escapes: valid
            // YAML, unreadable for exactly the titles the feature exists to preserve.
            file.readText(StandardCharsets.UTF_8) shouldContain "Начало"
            (loadYaml(file) as MappingLoad.Loaded).value.byEpisode["01"] shouldBe "Начало"
        }

        test("an empty show name and season 0 are written as given") {
            val workDir = tempdir()
            // Both are real inputs: an empty show is a case under test, and season 0 is where TheMovieDB
            // keeps specials. Defaults rather than nullables is what lets a caller say either.
            val file = writeEpisodesYaml(workDir, show = "", season = 0, episodes = mapOf(1 to "Special"))

            val loaded = loadYaml(file) as MappingLoad.Loaded
            loaded.value.show shouldBe ""
            loaded.value.season shouldBe "00"
        }

        test("the optional keys are omitted rather than written empty") {
            val workDir = tempdir()
            val text = writeEpisodesYaml(workDir, episodes = mapOf(1 to "First")).readText(StandardCharsets.UTF_8)

            listOf("year", "seasonName", "language").forEach { key ->
                check(!text.contains(key)) { "$key should be absent when not supplied, but the file reads:\n$text" }
            }
        }
    }
})
