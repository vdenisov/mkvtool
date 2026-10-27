package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.e2e.support.Run
import org.plukh.mkvtool.e2e.support.groupedCfg
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvmerge
import org.plukh.mkvtool.e2e.support.outputNames
import org.plukh.mkvtool.e2e.support.probe
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.writeConfig
import java.io.File

/**
 * `mux` over a config declaring groups: one pass per group, each muxing its own episodes with its own
 * selection, and what happens to an episode no group claims or two groups do.
 */
class MuxGroupsTest : FunSpec({

    test("a dry run labels each pass, and each group's command lines carry its own selection")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = season(tempdir(), 4)
        writeConfig(workDir, groupedCfg(TWO_GROUPS))

        val run = mux(workDir, "--dry-run")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "*** trackOrder not configured for group 1 (bd); using derived order: 0:0,0:1\n"
            run.output shouldContain "*** Pass 1 of 2: group 1 (bd), 2 files\n"
            run.output shouldContain "*** Pass 2 of 2: group 2 (tv), 2 files\n"
            run.output shouldContain "*** Pass 1 of 2: group 1 (bd)\n"
            run.output shouldContain "*** Pass 2 of 2: group 2 (tv)\n"
            commandOf(run, "Show - S01E01.mkv") shouldContain "--audio-tracks 1 "
            commandOf(run, "Show - S01E03.mkv") shouldContain "--audio-tracks 2 "
            File(workDir, "mkv").exists() shouldBe false
        }
    }

    test("each group's outputs carry its own tracks").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = season(tempdir(), 4)
        writeConfig(workDir, groupedCfg(TWO_GROUPS))

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            outputNames(workDir) shouldBe (1..4).map { "Show - S01E0$it.mkv" }
            // The bd group's audio is a shared track, so the use: reached the command line through the parser.
            audioOf(File(workDir, "mkv/Show - S01E01.mkv")) shouldBe listOf("jpn" to "Japanese")
            audioOf(File(workDir, "mkv/Show - S01E03.mkv")) shouldBe listOf("eng" to "English")
        }
    }

    test("an episode no group claims is skipped with a warning, and the rest is muxed")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = season(tempdir(), 5)
        writeConfig(workDir, groupedCfg(TWO_GROUPS))

        val run = mux(workDir, "--dry-run")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "*** 1 file will be skipped: not claimed by any group\n      Show - S01E05.mkv\n"
            run.output shouldNotContain "*** Processing Show - S01E05.mkv"
            run.output shouldContain "*** Processing Show - S01E04.mkv"
        }
    }

    test("an episode two groups claim stops the batch before anything is written")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = season(tempdir(), 4)
        // tv also claims E01 by a mask, which bd claims by its range.
        val doubleClaim = TWO_GROUPS.replace("episodes: \"3-4\"", "episodes: \"3-4\"\n        files: \"*E01*\"")
        writeConfig(workDir, groupedCfg(doubleClaim))

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain
                "Show - S01E01.mkv is claimed by more than one group: group 1 (bd), group 2 (tv)"
            run.output shouldContain "*** Nothing was muxed. Fix the file names or the config."
            File(workDir, "mkv").exists() shouldBe false
        }
    }
})

/**
 * Two groups over test.mkv's tracks: `bd` (episodes 1-2) copies the Japanese audio, through a shared track, and
 * `tv` (episodes 3-4) the English.
 */
private val TWO_GROUPS = """
    sharedTracks:
      - name: "jpn"
        id: 1
        language: "ja"
        title: "Japanese"
        default: true
    groups:
      - name: "bd"
        episodes: "1-2"
        mainSource:
          videoTrack:
            language: "ja"
          audioTracks:
            - use: "jpn"
      - name: "tv"
        episodes: "3-4"
        mainSource:
          videoTrack:
            language: "ja"
          audioTracks:
            - id: 2
              language: "en"
              title: "English"
              default: true
"""

/** [workDir] holding [count] episodes named by `SxxEyy`. */
private fun season(workDir: File, count: Int): File {
    (1..count).forEach { stageInput(workDir, "Show - S01E0$it.mkv") }
    return workDir
}

/** The dry-run command printed for [name]: everything between its header and the next. */
private fun commandOf(run: Run, name: String): String =
    run.output.substringAfter("*** Processing $name\n").substringBefore("*** Processing")

private fun audioOf(file: File): List<Pair<String?, String?>> =
    probe(file).allTracks.filter { it.type == "audio" }.map { it.language to it.trackName }

/** Output with the platform separator folded to a bare newline, for the multi-line assertions. */
private fun mux(workDir: File, vararg args: String): Run =
    mkvtool("mux", *args, workDir = workDir).let { it.copy(output = it.output.replace("\r\n", "\n")) }
