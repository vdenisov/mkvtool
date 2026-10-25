package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.e2e.support.Smoke
import org.plukh.mkvtool.e2e.support.TrackType
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvmerge
import org.plukh.mkvtool.e2e.support.stageExternalText
import org.plukh.mkvtool.e2e.support.stageExternalTrack
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.writeEpisodes
import org.plukh.mkvtool.e2e.support.writeEpisodesYaml
import java.io.File

/**
 * `rename` gives every file its canonical `Show - SxxEyy - Title[suffix].ext` name, or touches nothing.
 *
 * Renaming removes the `SxxEyy` that ties a file to its episode, so a batch that failed halfway would have to be
 * untangled by hand — which is why the refusals here assert on the files left on disk, not only on the output.
 */
class RenameTest : FunSpec({

    test("a missing title refuses the whole batch and touches nothing") {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e01.mkv")
        stageInput(workDir, "Show.s01e02.mkv")
        writeEpisodes(workDir, listOf("First Episode"))

        val run = mkvtool("rename", "My Show", workDir = workDir)

        withClue(run.output) {
            run.exitCode shouldNotBe 0
            run.output shouldContain "Refusing to rename"
            run.output shouldContain "no title for episode 02"
            // The valid part of the batch is still shown, so the blocked scope is visible.
            run.output shouldContain "My Show - S01E01 - First Episode.mkv"
        }
        File(workDir, "Show.s01e01.mkv").exists() shouldBe true
        File(workDir, "Show.s01e02.mkv").exists() shouldBe true
        workDir.list().orEmpty().filter { "null" in it } shouldBe emptyList()
    }

    test("--dry-run previews and renames nothing") {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e01.mkv")
        stageInput(workDir, "Show.s01e02.mkv")
        writeEpisodes(workDir, listOf("First Episode", "Second Episode"))

        val run = mkvtool("rename", "My Show", "1", "--dry-run", workDir = workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "My Show - S01E01 - First Episode.mkv"
            run.output shouldContain "My Show - S01E02 - Second Episode.mkv"
        }
        File(workDir, "Show.s01e01.mkv").exists() shouldBe true
        File(workDir, "Show.s01e02.mkv").exists() shouldBe true
        File(workDir, "My Show - S01E01 - First Episode.mkv").exists() shouldBe false
    }

    test("episodes.yaml wins over episodes.txt and is sanitized into the file name").config(tags = setOf(Smoke)) {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e01.mkv")
        stageInput(workDir, "Show.s01e02.mkv")
        // The two sources disagree on purpose.
        writeEpisodes(workDir, listOf("From Text One", "From Text Two"))
        writeEpisodesYaml(workDir, show = "My Show", episodes = mapOf(1 to "Slash/Colon: Question?", 2 to "Plain Two"))

        val run = mkvtool("rename", workDir = workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "Show name from episodes.yaml"
        }
        File(workDir, "My Show - S01E01 - SlashColon Question.mkv").exists() shouldBe true
        File(workDir, "My Show - S01E02 - Plain Two.mkv").exists() shouldBe true
    }

    // A season downloaded in halves is what the offset is for: it says which episode the first line of
    // episodes.txt is.
    test("episodeOffset shifts episodes.txt") {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e11.mkv")
        writeEpisodes(workDir, listOf("Eleventh", "Twelfth"))

        val run = mkvtool("rename", "My Show", "11", workDir = workDir)

        withClue(run.output) { run.exitCode shouldBe 0 }
        File(workDir, "My Show - S01E11 - Eleventh.mkv").exists() shouldBe true
    }

    // The yaml carries real episode numbers, so applying the offset out of habit would put episode 1's title on
    // E11 — plausible-looking and wrong.
    test("episodeOffset is ignored for episodes.yaml") {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e11.mkv")
        writeEpisodesYaml(workDir, show = "My Show", episodes = (1..12).associateWith { "Episode $it" })

        val run = mkvtool("rename", "My Show", "11", workDir = workDir)

        withClue(run.output) { run.exitCode shouldBe 0 }
        File(workDir, "My Show - S01E11 - Episode 11.mkv").exists() shouldBe true
        File(workDir, "My Show - S01E11 - Episode 1.mkv").exists() shouldBe false
    }

    // E100 was once read as E10; and one season writes one width, so its names sort in episode order.
    test("a season past ninety-nine reads three digits and writes every episode at three") {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e05.mkv")
        stageInput(workDir, "Show.s01e10.mkv")
        stageInput(workDir, "Show.s01e100.mkv")
        writeEpisodesYaml(workDir, show = "My Show", episodes = (1..100).associateWith { "Episode $it" })

        val run = mkvtool("rename", workDir = workDir)

        withClue(run.output) { run.exitCode shouldBe 0 }
        File(workDir, "My Show - S01E005 - Episode 5.mkv").exists() shouldBe true
        File(workDir, "My Show - S01E010 - Episode 10.mkv").exists() shouldBe true
        File(workDir, "My Show - S01E100 - Episode 100.mkv").exists() shouldBe true
    }

    test("no episode metadata at all exits 2 naming both files") {
        val workDir = tempdir()

        val run = mkvtool("rename", "My Show", workDir = workDir)

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "episodes.yaml or episodes.txt"
            run.output shouldNotContain "Exception"
        }
    }

    test("episodes.txt alone still renames, and an already sanitized line stays as it is") {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e01.mkv")
        writeEpisodes(workDir, listOf("Already Sanitized"))

        val run = mkvtool("rename", "My Show", workDir = workDir)

        withClue(run.output) { run.exitCode shouldBe 0 }
        File(workDir, "My Show - S01E01 - Already Sanitized.mkv").exists() shouldBe true
    }

    test("no show name anywhere exits 2") {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e01.mkv")
        writeEpisodesYaml(workDir, show = "", episodes = mapOf(1 to "One"))

        val run = mkvtool("rename", workDir = workDir)

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "No show name"
        }
    }

    // Three runs over one fixture rather than three cases, since building the fixture runs mkvmerge.
    test("--external renames the whole tree in place").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        listOf("01", "02").forEach { ep ->
            stageInput(workDir, "S01E$ep.mkv")
            stageExternalTrack(workDir, "Rus sound/[GroupA]/S01E$ep.mka", TrackType.AUDIO, 3)
        }
        stageExternalText(workDir, "S01E01.rus.srt")
        writeEpisodes(workDir, listOf("First Episode", "Second Episode"))
        val group = File(workDir, "Rus sound/[GroupA]")

        val plain = mkvtool("rename", "My Show", "1", "--dry-run", workDir = workDir)
        withClue(plain.output) {
            plain.exitCode shouldBe 0
            plain.output shouldNotContain "Rus sound"
        }

        val dry = mkvtool("rename", "My Show", "1", "--external", "--dry-run", workDir = workDir)
        withClue(dry.output) {
            dry.exitCode shouldBe 0
            dry.output shouldContain "Rus sound/[GroupA]/S01E01.mka"
            dry.output shouldContain "My Show - S01E01 - First Episode.mka"
        }
        File(group, "S01E01.mka").exists() shouldBe true

        val run = mkvtool("rename", "My Show", "1", "--external", workDir = workDir)
        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "external"
        }
        // In place: the directory is the variant's identity, so it never changes.
        File(group, "My Show - S01E01 - First Episode.mka").exists() shouldBe true
        File(group, "My Show - S01E02 - Second Episode.mka").exists() shouldBe true
        File(group, "S01E01.mka").exists() shouldBe false
        File(workDir, "My Show - S01E01 - First Episode.rus.srt").exists() shouldBe true
    }

    // Check everything before touching anything has to hold across the whole tree, not just this directory.
    test("--external refuses everything when any target is taken").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "S01E01.mkv")
        stageExternalTrack(workDir, "Rus sound/[GroupA]/S01E01.mka", TrackType.AUDIO, 3)
        stageExternalText(workDir, "Rus sound/[GroupA]/My Show - S01E01 - First Episode.mka", "occupied\n")
        writeEpisodes(workDir, listOf("First Episode"))

        val run = mkvtool("rename", "My Show", "1", "--external", workDir = workDir)

        withClue(run.output) {
            run.exitCode shouldBe 1
            run.output shouldContain "Refusing to rename anything"
        }
        File(workDir, "S01E01.mkv").exists() shouldBe true
        File(workDir, "Rus sound/[GroupA]/S01E01.mka").exists() shouldBe true
    }
})
