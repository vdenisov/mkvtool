package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.e2e.support.Run
import org.plukh.mkvtool.e2e.support.TrackType
import org.plukh.mkvtool.e2e.support.extractTrack
import org.plukh.mkvtool.e2e.support.fullCopy
import org.plukh.mkvtool.e2e.support.groupedCfg
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvmerge
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.testMkv
import org.plukh.mkvtool.e2e.support.writeConfig
import java.io.File

/**
 * `inspect` over a config declaring groups: one consistency check per group, against that group's selection;
 * each episode's configured sources from the group claiming it; and an episode no group claims listed and left
 * out of the checks.
 */
class InspectGroupsTest : FunSpec({

    test("each group is checked against its own selection, so one difference blocks in one and not the other")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = season(tempdir())

        val run = inspect(workDir)
        val raw = run.output.substringAfter("*** Consistency check, group 1 (raw): 2 files\n")
            .substringBefore("*** Consistency check, group 2")
        val dubbed = run.output.substringAfter("*** Consistency check, group 2 (dubbed): 2 files\n")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldNotContain "does not report per group"
            raw shouldContain "*** 1 informational (does not affect what gets muxed):\n" +
                "      track 2 (audio) - name differs across 2 groups\n"
            raw shouldNotContain "that the config selects"
            dubbed shouldContain "*** 1 discrepancy affects a track that the config selects:\n" +
                "      track 2 (audio, config title \"English\") - name differs across 2 groups\n"
        }

        val strict = inspect(workDir, "--strict")
        withClue(strict.output) {
            strict.exitCode shouldBe 2
            strict.output shouldContain "*** Strict mode: 1 discrepancy affecting selected tracks."
            strict.output shouldNotContain "config problem"
            strict.output shouldNotContain "binding problem"
        }
    }

    test("--identify lists each episode's sources from the group claiming it")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = season(tempdir())

        val run = inspect(workDir, "--identify")

        withClue(run.output) {
            run.exitCode shouldBe 0
            blockOf(run, "Show - S01E03.mkv") shouldContain "  + Show - S01E03[Studio].mka\n"
            blockOf(run, "Show - S01E04.mkv") shouldContain "  + Show - S01E04[Studio].mka\n"
            blockOf(run, "Show - S01E01.mkv") shouldNotContain "[Studio]"
        }
    }

    test("an episode no group claims is listed as skipped and left out of every check")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = season(tempdir())

        val run = inspect(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "*** 1 file will be skipped: not claimed by any group\n      Show - S01E05.mkv\n"
            run.output shouldContain "*** Consistency check, group 1 (raw): 2 files\n"
            run.output shouldContain "*** Consistency check, group 2 (dubbed): 2 files\n"
        }
    }
})

/**
 * `raw` (episodes 1-2) copies audio 1, and `dubbed` (episodes 3-4) audio 2 plus a Studio dub. Audio 2's name
 * differs within each group, which only `dubbed` selects.
 */
private val GROUPS = """
    groups:
      - name: "raw"
        episodes: "1-2"
        mainSource:
          audioTracks:
            - id: 1
              language: "ja"
      - name: "dubbed"
        episodes: "3-4"
        mainSource:
          audioTracks:
            - id: 2
              language: "en"
              title: "English"
        additionalSources:
          - file: "${'$'}{fileName}[Studio].mka"
            tracks:
              - language: "ru"
"""

/** [workDir] holding episodes 1-4 for [GROUPS], audio 2 renamed in E02 and E04, and an E05 no group claims. */
private fun season(workDir: File): File {
    (1..4).forEach { ep ->
        val names = if (ep % 2 == 0) mapOf(2 to "Other Studio") else emptyMap()
        fullCopy(File(workDir, "Show - S01E0$ep.mkv"), names = names)
    }
    (3..4).forEach { extractTrack(testMkv, File(workDir, "Show - S01E0$it[Studio].mka"), TrackType.AUDIO, 2) }
    stageInput(workDir, "Show - S01E05.mkv")
    writeConfig(workDir, groupedCfg(GROUPS))
    return workDir
}

/** The `--identify` block printed for [name]: everything between its header and the next. */
private fun blockOf(run: Run, name: String): String =
    run.output.substringAfter("*** $name\n").substringBefore("\n*** ")

/** Output with the platform separator folded to a bare newline, for the multi-line assertions. */
private fun inspect(workDir: File, vararg args: String): Run =
    mkvtool("inspect", *args, workDir = workDir).let { it.copy(output = it.output.replace("\r\n", "\n")) }
