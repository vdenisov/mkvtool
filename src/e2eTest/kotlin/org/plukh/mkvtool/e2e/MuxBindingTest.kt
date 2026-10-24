package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.plukh.mkvtool.e2e.support.AdditionalSourceSpec
import org.plukh.mkvtool.e2e.support.Run
import org.plukh.mkvtool.e2e.support.SourceTrackSpec
import org.plukh.mkvtool.e2e.support.TrackSpec
import org.plukh.mkvtool.e2e.support.cfg
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvmerge
import org.plukh.mkvtool.e2e.support.outputNames
import org.plukh.mkvtool.e2e.support.probe
import org.plukh.mkvtool.e2e.support.stageExternalText
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.writeConfig
import org.plukh.mkvtool.e2e.support.writeEpisodesYaml
import java.io.File

/**
 * `mux` over a config that binds files to episodes: raw release names muxed as they are, a source's files
 * paired with main files, what is skipped and what stops the batch — plus the output-collision check,
 * which every run gets.
 */
class MuxBindingTest : FunSpec({

    test("a raw release binds by its regex, through the map, and is muxed under outputName")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "[Grp] Show - 13 (BD).mkv")
        stageInput(workDir, "[Grp] Show - 14 (BD).mkv")
        writeEpisodesYaml(workDir, show = "Show", episodes = mapOf(1 to "One: Start", 2 to "Two"))
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(JAPANESE),
                generalExtra = listOf(
                    """episodeRegex: ' - (\d+) \('""",
                    "episodeMap:",
                    "  13-14: 1-2",
                    "outputName: '\${showName} - S\${seasonNum}E\${episodeNum} - \${episodeName}'",
                ),
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            // The colon is dropped from the file name; the metadata keeps it.
            outputNames(workDir) shouldBe listOf("Show - S01E01 - One Start.mkv", "Show - S01E02 - Two.mkv")
        }
    }

    test("a source's file pairs with its main file by the source's own regex, and is muxed in")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "A - S01E01.mkv")
        stageExternalText(workDir, "Subs/01.srt")
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(JAPANESE),
                additionalSources = listOf(
                    AdditionalSourceSpec(
                        files = listOf("Subs/*.srt"),
                        episodeRegex = "^(\\d+)$",
                        tracks = listOf(SourceTrackSpec(language = "en", title = "English")),
                    ),
                ),
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            val tracks = probe(File(workDir, "mkv/A - S01E01.mkv")).allTracks
            tracks.filter { it.type == "subtitles" }.map { it.language } shouldBe listOf("eng")
        }
    }

    test("a main file the rule cannot read is warned about and not muxed")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Show E01.mkv")
        stageInput(workDir, "NCOP.mkv")
        writeEpisodesYaml(workDir, episodes = mapOf(1 to "One"))
        writeConfig(workDir, cfg(audioTracks = listOf(JAPANESE), generalExtra = listOf("""episodeRegex: 'E(\d+)'""")))

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "*** Warning: 1 main file has no episode and will be skipped:\n      NCOP.mkv"
            outputNames(workDir) shouldBe listOf("Show E01.mkv")
        }
    }

    test("a main file whose source file is missing is dropped, and the rest of the batch is muxed")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "A - S01E01.mkv")
        stageInput(workDir, "A - S01E02.mkv")
        stageExternalText(workDir, "Subs/A - S01E01.srt")
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(JAPANESE),
                additionalSources = listOf(
                    AdditionalSourceSpec(
                        files = listOf("Subs/*.srt"),
                        tracks = listOf(SourceTrackSpec(language = "en", title = "English")),
                    ),
                ),
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "*** 1 file will be skipped: companion files are missing"
            run.output shouldContain "additionalSources[0].files: Subs/*.srt  (missing for 1 file)"
            outputNames(workDir) shouldBe listOf("A - S01E01.mkv")
        }
    }

    test("two main files on one episode stop the batch before anything is written")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "A E01.mkv")
        stageInput(workDir, "B E01.mkv")
        writeEpisodesYaml(workDir, episodes = mapOf(1 to "One"))
        writeConfig(workDir, cfg(audioTracks = listOf(JAPANESE), generalExtra = listOf("""episodeRegex: 'E(\d+)'""")))

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "*** Error: the files cannot be bound to episodes (1 problem):"
            run.output shouldContain "episode 1 is claimed by 2 main files: A E01.mkv, B E01.mkv"
            run.output shouldContain "*** Nothing was muxed. Fix the file names or the config."
        }
        File(workDir, "mkv").exists() shouldBe false
    }

    test("an outputName two files resolve alike stops the batch before anything is written")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "a.mkv")
        stageInput(workDir, "b.mkv")
        writeConfig(workDir, cfg(audioTracks = listOf(JAPANESE), generalExtra = listOf("outputName: 'Show'")))

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "*** Error: 2 files would be written to mkv/Show.mkv:\n      a.mkv\n      b.mkv"
        }
        File(workDir, "mkv").exists() shouldBe false
    }

    // No binding key here: two main files sharing a base name would overwrite one output with the other.
    test("two main files sharing a base name stop a v1 batch too").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "a.mkv")
        stageInput(workDir, "a.mp4")
        writeConfig(workDir, cfg(audioTracks = listOf(JAPANESE)))

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "*** Error: 2 files would be written to mkv/a.mkv:"
        }
        File(workDir, "mkv").exists() shouldBe false
    }
})

private val JAPANESE = TrackSpec(id = 1, language = "ja", title = "Japanese", default = true)

/** Output with the platform separator folded to a bare newline, for the multi-line assertions. */
private fun mux(workDir: File): Run =
    mkvtool("mux", workDir = workDir).let { it.copy(output = it.output.replace("\r\n", "\n")) }
