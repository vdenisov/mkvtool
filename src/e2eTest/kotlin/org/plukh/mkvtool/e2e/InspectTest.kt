package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.e2e.support.AdditionalSourceSpec
import org.plukh.mkvtool.e2e.support.Run
import org.plukh.mkvtool.e2e.support.Smoke
import org.plukh.mkvtool.e2e.support.SourceTrackSpec
import org.plukh.mkvtool.e2e.support.TrackSpec
import org.plukh.mkvtool.e2e.support.TrackType
import org.plukh.mkvtool.e2e.support.cfg
import org.plukh.mkvtool.e2e.support.checkCfg
import org.plukh.mkvtool.e2e.support.extractTrack
import org.plukh.mkvtool.e2e.support.fullCopy
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvmerge
import org.plukh.mkvtool.e2e.support.stageBatch
import org.plukh.mkvtool.e2e.support.stageExternalText
import org.plukh.mkvtool.e2e.support.stageExternalTrack
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.stageTree
import org.plukh.mkvtool.e2e.support.writeConfig
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * `inspect` reports and never writes: nothing about a config can stop it, and only `--strict` turns what it
 * finds into a non-zero exit. These are the per-file `--identify` cases and the spine both modes share - the
 * mode rule, discovery, and the guards around the optional config and episode metadata.
 */
class InspectTest : FunSpec({

    test("--identify lists tracks and writes nothing").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        val run = inspect(workDir, "--identify")

        withClue(run.output) {
            run.exitCode shouldBe 0
            // Loose on purpose: column formatting is expected to drift.
            run.output shouldContain "video"
            run.output shouldContain "subtitles"
            run.output shouldContain "jpn"
        }
        outputDir(workDir).exists() shouldBe false
    }

    test("masks narrow --identify to the named file").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageBatch(workDir)

        val run = inspect(workDir, "--identify", "Show.S01E02.mkv")

        withClue(run.output) {
            run.exitCode shouldBe 0
            // The per-file header, not the bare name: a mask selecting nothing echoes the name back too.
            run.output shouldContain "*** Show.S01E02.mkv"
            run.output shouldNotContain "Show.S01E01.mkv"
        }
        outputDir(workDir).exists() shouldBe false
    }

    test("--identify lists configured companions, missing ones included").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        val input = stageInput(workDir, "My Show - S01E01 - Pilot.mkv")
        extractTrack(input, File(workDir, "My Show - S01E01 - Pilot.rus.mka"), TrackType.AUDIO, 3)
        // No companion for this one.
        stageInput(workDir, "My Show - S01E02 - Second.mkv")
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                additionalSources = listOf(
                    AdditionalSourceSpec(
                        file = "\${fileName}.rus.mka",
                        tracks = listOf(SourceTrackSpec(language = "ru", title = "Russian")),
                    ),
                ),
                trackOrder = "0:0,0:2,1:0",
            ),
        )

        val run = inspect(workDir, "--identify")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "+ My Show - S01E01 - Pilot.rus.mka"
            // --identify describes what exists rather than asserting it, so an absent source is not an error.
            run.output shouldContain "(not found)"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // Both modes in one run, which is what makes it the representative inspect case. The with-config variant of
    // the mode table's last row, which Kotest cannot tag on its own. That the modes share one probe per file is
    // pinned in the unit tier, where the probe is a counting fake.
    test("--identify and --check together print both")
        .config(tags = setOf(Smoke), enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "S01E01.mkv"))
        writeConfig(workDir, checkCfg())

        val run = inspect(workDir, "--identify", "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "Consistency check"
            run.output shouldContain "*** S01E01.mkv"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // The batch report is the default because it is the question one usually arrives with; --identify is the
    // per-file drill-down. None of it needs a config.
    context("a bare run checks, --identify identifies, naming both runs both")
        .config(enabledOrReasonIf = needsMkvmerge) {
        withData(
            nameFn = { it.label },
            ModeRow("a bare run", emptyList(), check = true, table = false),
            ModeRow("--identify alone", listOf("--identify"), check = false, table = true),
            ModeRow("--identify and --check", listOf("--identify", "--check"), check = true, table = true),
        ) { row ->
            val workDir = tempdir()
            // No config.yaml is staged: none is needed, in any mode.
            fullCopy(File(workDir, "a.mkv"))
            fullCopy(File(workDir, "b.mkv"))

            val run = inspect(workDir, *row.args.toTypedArray())

            withClue(run.output) {
                run.exitCode shouldBe 0
                run.output.contains("Consistency check") shouldBe row.check
                run.output.contains("*** a.mkv") shouldBe row.table
            }
            outputDir(workDir).exists() shouldBe false
        }
    }

    // A green "Done" in an empty directory would look identical to a run that had no work - after a typo'd
    // cd, say.
    test("an empty directory says there are no media files").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "No media files"
            run.output shouldContain "mkv"
        }
    }

    test("a mask matching only non-media files says so").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        File(workDir, "notes.txt").writeText("not media", StandardCharsets.UTF_8)

        val run = inspect(workDir, "--identify", "notes.txt")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "No media files match: notes.txt"
        }
        outputDir(workDir).exists() shouldBe false
    }

    test("--identify bundles external files under each episode").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageTree(workDir)

        val run = inspect(workDir, "--identify")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "External files:"
            run.output shouldContain "Rus sound/[GroupA]/<name>.mka"
            // A merged variant lists both kinds of file it holds, and the suffix layout is a variant too.
            run.output shouldContain "Rus subs/[GroupA]/<name>.ass"
            run.output shouldContain "<name>.rus.srt"

            // A probed .mka reports the language it carries; an unprobed .ass falls back to the directory
            // guess, marked as one, and takes its codec from the extension.
            run.output shouldContain "rus?"
            run.output shouldContain "ASS"
            // One block with both its files under it, not two blocks that happen to share a label.
            run.output shouldContain "[GroupA] (.mka, .ass)"

            // [GroupB] is staged untagged, which mkvmerge reports as 'und' and which has to fall back to the
            // folder's guess. Matched as whole audio rows: the fixture's own video track is genuinely
            // undefined, and "Rus sound" contains the letters too. The positive half keeps the negative one
            // honest - if the row format drifted, the 'und' pattern would match nothing and pass regardless.
            val audioRow = { lang: String -> Regex("""^\s+\d+\s+audio\s+\S+\s+$lang\s.*""") }
            run.output.lines().filter { audioRow("und").matches(it) } shouldBe emptyList()
            run.output.lines().count { audioRow("""rus\?""").matches(it) } shouldBe 1

            run.output shouldContain "Unmatched external files"
            run.output shouldContain "Bonus.ass"
            // A main-type file in a subdirectory is an extra, never an episode.
            run.output shouldContain "Extras:"
            run.output shouldContain "Sample.mkv"
            run.output shouldNotContain "*** extras/Sample.mkv"
        }
    }

    // Muxed output carries its sources' base names, so a destination directory left in place would come back
    // as an external file of the episode it was made from.
    test("discovery skips dot-directories and the output directory").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Show - S01E01 - Title.mkv")
        stageExternalTrack(workDir, "mkv/Show - S01E01 - Title.mka", TrackType.AUDIO, 3)
        stageExternalText(workDir, ".stash/Show - S01E01 - Title.srt")
        stageExternalText(workDir, "Rus subs/[Real]/Show - S01E01 - Title.ass")
        writeConfig(
            workDir,
            cfg(audioTracks = listOf(TrackSpec(id = 1, language = "jpn", title = "JP", default = true))),
        )

        val run = inspect(workDir, "--identify")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "[Real]"
            run.output shouldNotContain "mkv/"
            run.output shouldNotContain ".stash"
        }
    }

    // On its own a .sub identifies as a zero-track MPEG stream, so probing it would report nothing useful and cost
    // a subprocess. Neither half is valid, so every probe shows as a failure, and exactly one failure is what
    // proves one half was left alone. That the half read is the .idx is pinned in the unit tier.
    test("of an idx/sub pair only one half is probed").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Show - S01E01 - Title.mkv")
        stageExternalText(workDir, "Subs/[Group]/Show - S01E01 - Title.idx", "not really an index\n")
        stageExternalText(workDir, "Subs/[Group]/Show - S01E01 - Title.sub", "not really a stream\n")

        val run = inspect(workDir, "--identify")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "VobSub"
            Regex("could not read this file").findAll(run.output).count() shouldBe 1
            run.output shouldNotContain "Exception"
        }
    }

    // Matching against the masked list would dump the rest of the season's dubs into "unmatched" - the
    // opposite of what a mask asks for.
    test("masks narrow what is displayed, not what is matched").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageTree(workDir)

        val run = inspect(workDir, "--identify", "Show - S01E01 - Title.mkv")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "*** Show - S01E01 - Title.mkv"
            run.output shouldNotContain "*** Show - S01E02 - Title.mkv"
            run.output shouldNotContain "Show - S01E02 - Title.mka"
            run.output shouldNotContain "Show - S01E02 - Title.ass"
        }
    }

    // The track table is how one would fix a stale or half-written config, so the config must never stand
    // between the user and it.
    context("a broken config never stops --identify").config(enabledOrReasonIf = needsMkvmerge) {
        withData(
            nameFn = { it.label },
            BrokenFile("malformed", "general:\n  destinationDir: mkv\n   bad indent: [\n"),
            BrokenFile("empty", ""),
            BrokenFile("not a map", "- just\n- a list\n"),
            BrokenFile(
                "bad token",
                "mainSource:\n  videoTrack:\n    language: \"jpn\"\n    title: \"\${epsiodeName}\"\n",
            ),
        ) { row ->
            val workDir = tempdir()
            stageInput(workDir, "a.mkv")
            File(workDir, "config.yaml").writeText(row.text, StandardCharsets.UTF_8)

            val run = inspect(workDir, "--identify")
            withClue(run.output) {
                run.exitCode shouldBe 0
                run.output shouldContain "*** a.mkv"
                run.output shouldNotContain "Exception"
                run.output shouldContain "Warning"
            }

            // --strict is the one thing that turns any of it into a failure.
            val strict = inspect(workDir, "--strict")
            withClue(strict.output) {
                strict.exitCode shouldBe 2
                strict.output shouldContain "config problem"
            }
            outputDir(workDir).exists() shouldBe false
        }
    }

    // The same rule one file over, plus a failure of its own: an episode number that is not a number throws
    // inside the transform, so it stays caught only while that runs inside the guard. Not a config problem:
    // episode metadata produces no findings, so there is nothing for --strict to fail on.
    context("a broken episodes.yaml never stops --identify, and is no config problem")
        .config(enabledOrReasonIf = needsMkvmerge) {
        withData(
            nameFn = { it.label },
            BrokenFile("malformed", "show: Test\nepisodes:\n  - episode: 1\n   name: [\n"),
            BrokenFile("empty", ""),
            BrokenFile("not a map", "- just\n- a list\n"),
            BrokenFile("bad number", "show: Test\nepisodes:\n  - episode: \"one\"\n    name: Pilot\n"),
        ) { row ->
            val workDir = tempdir()
            stageInput(workDir, "Show - S01E01 - Title.mkv")
            File(workDir, "episodes.yaml").writeText(row.text, StandardCharsets.UTF_8)

            val run = inspect(workDir, "--identify")
            withClue(run.output) {
                run.exitCode shouldBe 0
                run.output shouldContain "*** Show - S01E01 - Title.mkv"
                run.output shouldNotContain "Exception"
                run.output shouldContain "Warning"
            }

            val strict = inspect(workDir, "--strict")
            withClue(strict.output) {
                strict.exitCode shouldBe 0
                strict.output shouldNotContain "config problem"
            }
        }
    }

    // A split that only a loaded config can classify: a run that ignored --config would say "Add a config".
    test("--config reads a config outside the working directory").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "a.mkv"))
        fullCopy(File(workDir, "b.mkv"), names = mapOf(2 to "Other Studio"))
        File(workDir, "configs").mkdirs()
        File(workDir, "configs/show.yaml").writeText(
            cfg(audioTracks = listOf(TrackSpec(id = 1, language = "ja", title = "JP", default = true))),
            StandardCharsets.UTF_8,
        )

        val run = inspect(workDir, "--check", "--config", "configs/show.yaml")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "informational"
            run.output shouldNotContain "Add a config"
            run.output shouldNotContain "Warning"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // A failed probe is not a never-probed format: describing a truncated .mka from its extension would
    // present the one file that cannot be muxed as the healthiest row in the table.
    test("an external file mkvmerge cannot read is reported, not faked").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Show - S01E01 - Title.mkv")
        stageExternalText(workDir, "Rus sound/[Group]/Show - S01E01 - Title.mka", "not a matroska file at all\n")

        val run = inspect(workDir, "--identify")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldNotContain "Exception"
            // The file's block holds the failure and no track row at all - whatever codec label a row faked
            // from the extension would carry.
            val lines = run.output.lines()
            val block = lines.drop(lines.indexOfFirst { it.trimStart().startsWith("+ [") } + 1)
                .takeWhile { it.isNotBlank() }
            block.any { "could not read this file" in it } shouldBe true
            val trackRow = Regex("""^\s+\d+\s+(video|audio|subtitles)\s.*""")
            block.filter { trackRow.matches(it) } shouldBe emptyList()
        }

        // The check builds a slot from the same failed probe, and must not trip over it either.
        val check = inspect(workDir)
        withClue(check.output) {
            check.exitCode shouldBe 0
            check.output shouldNotContain "Exception"
        }
    }
})

/** One mode of a bare-versus-`--identify` run: which of the two outputs it must print. */
private class ModeRow(val label: String, val args: List<String>, val check: Boolean, val table: Boolean)

/** A hand-editable file in one of the ways it breaks. */
private class BrokenFile(val label: String, val text: String)

private fun inspect(workDir: File, vararg args: String): Run = mkvtool("inspect", *args, workDir = workDir)

private fun outputDir(workDir: File): File = File(workDir, "mkv")
