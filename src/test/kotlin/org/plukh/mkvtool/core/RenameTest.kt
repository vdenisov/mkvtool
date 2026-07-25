package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * Plan construction, which is the whole of `rename`'s judgement: by the time anything is renamed the
 * decisions are all made. Every case here asserts on the plan rather than on the directory, except the
 * two that exist to prove nothing was touched.
 */
class RenameTest : FunSpec({

    val names = mapOf("01" to "First Episode", "02" to "Second Episode")

    fun plan(dir: File, external: Boolean = false, showName: String = "My Show") =
        buildRenamePlan(dir, showName, names, "episodes.txt", external)

    context("loadEpisodeSource") {
        test("episodes.yaml wins over episodes.txt, and supplies the show name") {
            val dir = tempdir()
            File(dir, "episodes.txt").writeText("From Text\n", Charsets.UTF_8)
            File(dir, "episodes.yaml").writeText(
                "show: My Show\nepisodes:\n- episode: 1\n  name: From Yaml\n",
                Charsets.UTF_8,
            )

            val source = loadEpisodeSource(dir, offset = 1).shouldBeInstanceOf<EpisodeSource.Loaded>()
            source.name shouldBe "episodes.yaml"
            source.data.show shouldBe "My Show"
            source.data.byEpisode shouldBe mapOf("01" to "From Yaml")
        }

        test("the offset numbers episodes.txt from its first line") {
            val dir = tempdir()
            File(dir, "episodes.txt").writeText("Eleventh\nTwelfth\n", Charsets.UTF_8)

            val source = loadEpisodeSource(dir, offset = 11).shouldBeInstanceOf<EpisodeSource.Loaded>()
            source.data.byEpisode shouldBe mapOf("11" to "Eleventh", "12" to "Twelfth")
        }

        test("the offset never reaches episodes.yaml, whose numbers are already real") {
            // Applying it out of habit would map episode 1's title onto E11 — plausible-looking and wrong.
            val dir = tempdir()
            File(dir, "episodes.yaml").writeText(
                "show: My Show\nepisodes:\n- episode: 1\n  name: One\n- episode: 2\n  name: Two\n",
                Charsets.UTF_8,
            )

            loadEpisodeSource(dir, offset = 11).shouldBeInstanceOf<EpisodeSource.Loaded>()
                .data.byEpisode shouldBe mapOf("01" to "One", "02" to "Two")
        }

        test("neither file is a clean problem, with the hint that says what to do") {
            val problem = loadEpisodeSource(tempdir(), offset = 1).shouldBeInstanceOf<EpisodeSource.Problem>()
            problem.message shouldContain "episodes.yaml or episodes.txt"
            problem.hint!! shouldContain "fetch-episodes"
        }

        test("a hand-edited episodes.yaml is classified, not stack-traced") {
            // v1 parsed this file bare and met an unreadable episode number with an exception; mux and
            // inspect both guard it, and this was the odd one out.
            val dir = tempdir()
            File(dir, "episodes.yaml").writeText(
                "show: My Show\nepisodes:\n- episode: one\n  name: First\n",
                Charsets.UTF_8,
            )

            loadEpisodeSource(dir, offset = 1).shouldBeInstanceOf<EpisodeSource.Problem>()
                .message shouldContain "there is nothing to rename by"
        }

        test("a malformed episodes.yaml is classified too") {
            val dir = tempdir()
            File(dir, "episodes.yaml").writeText("show: [unclosed\n", Charsets.UTF_8)

            loadEpisodeSource(dir, offset = 1).shouldBeInstanceOf<EpisodeSource.Problem>()
                .message shouldContain "could not parse episodes.yaml"
        }
    }

    context("plan construction") {
        test("names each file from its own season and episode, keeping its extension") {
            val dir = tempdir()
            touch(dir, "Show.s01e01.mkv")
            touch(dir, "Show.s01e02.avi")

            plan(dir).entries.map { it.newName } shouldContainExactlyInAnyOrder listOf(
                "My Show - S01E01 - First Episode.mkv",
                "My Show - S01E02 - Second Episode.avi",
            )
        }

        test("a trailing [Studio] survives, since it says which group the file belongs to") {
            val dir = tempdir()
            touch(dir, "Show.s01e01[Dub Studio].mkv")

            plan(dir).entries.single().newName shouldBe "My Show - S01E01 - First Episode[Dub Studio].mkv"
        }

        test("files of no interest are left out entirely") {
            val dir = tempdir()
            touch(dir, "Show.s01e01.mkv")
            touch(dir, "notes.txt")
            touch(dir, "cover.jpg")

            plan(dir).entries.map { it.file.name } shouldContainExactly listOf("Show.s01e01.mkv")
        }

        test("a name with no SxxEyy in it stops the batch rather than being skipped") {
            val dir = tempdir()
            touch(dir, "Show.s01e01.mkv")
            touch(dir, "bonus feature.mkv")

            val result = plan(dir)
            result.problems.single().shouldBeInstanceOf<RenameProblem.NoEpisodeNumber>().fileName shouldBe
                "bonus feature.mkv"
            // The rest of the batch is still planned, so the report can show what is blocked.
            result.entries.single().newName shouldBe "My Show - S01E01 - First Episode.mkv"
        }

        test("an episode the metadata has no title for names the file that needed it") {
            val dir = tempdir()
            touch(dir, "Show.s01e09.mkv")

            val problem = plan(dir).problems.single().shouldBeInstanceOf<RenameProblem.NoTitle>()
            problem.episode shouldBe "09"
            problem.source shouldBe "episodes.txt"
            problem.fileName shouldBe "Show.s01e09.mkv"
        }

        test("a taken target is refused rather than overwritten") {
            val dir = tempdir()
            touch(dir, "Show.s01e01.mkv")
            touch(dir, "My Show - S01E01 - First Episode.mkv")

            val problem = plan(dir).problems.single().shouldBeInstanceOf<RenameProblem.TargetExists>()
            problem.path shouldBe "My Show - S01E01 - First Episode.mkv"
            problem.source shouldBe "Show.s01e01.mkv"
        }

        test("a file already carrying its new name is not a collision with itself") {
            val dir = tempdir()
            touch(dir, "My Show - S01E01 - First Episode.mkv")

            val result = plan(dir)
            result.problems.shouldBeEmpty()
            result.entries.single().newName shouldBe "My Show - S01E01 - First Episode.mkv"
        }

        test("two files wanting one name is caught before either is written") {
            val dir = tempdir()
            touch(dir, "a.s01e01.mkv")
            touch(dir, "b.s01e01.mkv")

            val problem = plan(dir).problems.single().shouldBeInstanceOf<RenameProblem.DuplicateTarget>()
            problem.newName shouldBe "My Show - S01E01 - First Episode.mkv"
            problem.fileNames shouldContainExactlyInAnyOrder listOf("a.s01e01.mkv", "b.s01e01.mkv")
        }
    }

    context("--external") {
        /** A main file per episode, a dub under its own directory, and a suffixed sibling. */
        fun tree(): File {
            val dir = tempdir()
            touch(dir, "S01E01.mkv")
            touch(dir, "S01E02.mkv")
            val group = File(dir, "Rus sound/[GroupA]").apply { mkdirs() }
            touch(group, "S01E01.mka")
            touch(group, "S01E02.mka")
            touch(dir, "S01E01.rus.srt")
            return dir
        }

        test("without the flag, nothing outside this directory is touched at all") {
            val result = plan(tree(), external = false)

            result.entries.none { it.external } shouldBe true
            result.entries.map { it.newName } shouldContainExactlyInAnyOrder listOf(
                "My Show - S01E01 - First Episode.mkv",
                "My Show - S01E02 - Second Episode.mkv",
                // Claimed by the ordinary rule, which keeps only a trailing "[...]" — so the sibling
                // loses its ".rus" here. That is exactly why --external claims it instead.
                "My Show - S01E01 - First Episode.srt",
            )
        }

        test("an external file takes the main's new base name, keeping its own suffix and directory") {
            val dir = tree()
            val result = plan(dir, external = true)

            val external = result.entries.filter { it.external }
            external.map { it.relPath } shouldContainExactlyInAnyOrder listOf(
                "Rus sound/[GroupA]/S01E01.mka",
                "Rus sound/[GroupA]/S01E02.mka",
                "S01E01.rus.srt",
            )
            external.map { it.newName } shouldContainExactlyInAnyOrder listOf(
                "My Show - S01E01 - First Episode.mka",
                "My Show - S01E02 - Second Episode.mka",
                "My Show - S01E01 - First Episode.rus.srt",
            )
            // The directory never changes: it is the variant's identity, so only the name is planned.
            external.filter { it.relPath!!.startsWith("Rus sound") }
                .forEach { it.file.parentFile shouldBe File(dir, "Rus sound/[GroupA]") }
        }

        test("a tier-1 claim beats the ordinary rule, so the sibling is planned once") {
            val result = plan(tree(), external = true)

            result.entries.count { it.file.name == "S01E01.rus.srt" } shouldBe 1
            result.entries.single { it.file.name == "S01E01.rus.srt" }.external shouldBe true
        }

        test("an episode-number match is reported and left alone, never guessed at") {
            val dir = tempdir()
            touch(dir, "S01E01.mkv")
            touch(File(dir, "Grp").apply { mkdirs() }, "AAA S01E01.mka")

            val result = plan(dir, external = true)

            result.skippedExternals shouldContainExactly listOf("Grp/AAA S01E01.mka")
            result.entries.none { it.external } shouldBe true
            result.problems.shouldBeEmpty()
        }

        test("a taken external target refuses the whole batch, main files included") {
            val dir = tempdir()
            touch(dir, "S01E01.mkv")
            val group = File(dir, "Rus sound/[GroupA]").apply { mkdirs() }
            touch(group, "S01E01.mka")
            touch(group, "My Show - S01E01 - First Episode.mka")

            val problem = plan(dir, external = true).problems.single()
                .shouldBeInstanceOf<RenameProblem.TargetExists>()
            // The path carries the directory: the same name elsewhere is a different target.
            problem.path shouldBe "Rus sound/[GroupA]/My Show - S01E01 - First Episode.mka"
            problem.source shouldBe "Rus sound/[GroupA]/S01E01.mka"
        }

        test("an external whose main file is blocked is quietly left out of the plan") {
            val dir = tempdir()
            touch(dir, "S01E09.mkv")   // the metadata has no title for episode 09
            touch(File(dir, "Rus sound/[GroupA]").apply { mkdirs() }, "S01E09.mka")

            val result = plan(dir, external = true)

            result.entries.shouldBeEmpty()
            result.problems.single().shouldBeInstanceOf<RenameProblem.NoTitle>()
        }
    }

    context("applying a plan") {
        test("a dry run touches nothing and reports every file as a preview") {
            val dir = tempdir()
            touch(dir, "Show.s01e01.mkv")

            val run = applyRenamePlan(plan(dir), dryRun = true, SilentRenderer)

            run.files.single().outcome shouldBe RenameOutcome.Previewed
            run.failed shouldBe 0
            File(dir, "Show.s01e01.mkv").exists() shouldBe true
            File(dir, "My Show - S01E01 - First Episode.mkv").exists() shouldBe false
        }

        test("a real run renames in place, external files in their own directories") {
            val dir = tempdir()
            touch(dir, "S01E01.mkv")
            touch(File(dir, "Rus sound/[GroupA]").apply { mkdirs() }, "S01E01.mka")

            val run = applyRenamePlan(plan(dir, external = true), dryRun = false, SilentRenderer)

            run.failed shouldBe 0
            run.external shouldBe 1
            File(dir, "My Show - S01E01 - First Episode.mkv").exists() shouldBe true
            File(dir, "Rus sound/[GroupA]/My Show - S01E01 - First Episode.mka").exists() shouldBe true
            File(dir, "Rus sound/[GroupA]/S01E01.mka").exists() shouldBe false
        }

        test("an external file is reported by path and a main file by name") {
            val dir = tempdir()
            touch(dir, "S01E01.mkv")
            touch(File(dir, "Rus sound/[GroupA]").apply { mkdirs() }, "S01E01.mka")

            val run = applyRenamePlan(plan(dir, external = true), dryRun = true, SilentRenderer)

            run.files.map { it.from } shouldContainExactlyInAnyOrder
                listOf("S01E01.mkv", "Rus sound/[GroupA]/S01E01.mka")
        }
    }
})

private fun touch(dir: File, name: String): File = File(dir, name).apply { writeText("x") }
