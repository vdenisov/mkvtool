package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.e2e.support.Run
import org.plukh.mkvtool.e2e.support.TrackSpec
import org.plukh.mkvtool.e2e.support.TrackType
import org.plukh.mkvtool.e2e.support.cfg
import org.plukh.mkvtool.e2e.support.checkCfg
import org.plukh.mkvtool.e2e.support.companionCfg
import org.plukh.mkvtool.e2e.support.extractTrack
import org.plukh.mkvtool.e2e.support.fullCopy
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvmerge
import org.plukh.mkvtool.e2e.support.outputNames
import org.plukh.mkvtool.e2e.support.pathShadowing
import org.plukh.mkvtool.e2e.support.posixOnly
import org.plukh.mkvtool.e2e.support.probe
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.testMkv
import org.plukh.mkvtool.e2e.support.writeConfig
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * What `mux` decides before it muxes anything: the companion pre-flight, the consistency check and its
 * `--strict` gate, and the refusals of a config, an `episodes.yaml` or a flag it cannot use.
 */
class MuxPreflightTest : FunSpec({

    // A dub released for some episodes only is normal: the missing ones are named up front and the rest
    // still mux, instead of mkvmerge failing partway through the batch.
    test("a missing companion skips that episode and muxes the rest").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Show.S01E01.mkv")
        stageInput(workDir, "Show.S01E02.mkv")
        extractTrack(testMkv, File(workDir, "Show.S01E01[Studio].mka"), TrackType.AUDIO, 2)
        writeConfig(workDir, companionCfg())

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "1 file will be skipped: companion files are missing"
            run.output shouldContain "\${fileName}[Studio].mka  (missing for 1 file)"
            run.output shouldContain "Show.S01E02.mkv"
        }
        outputNames(workDir) shouldBe listOf("Show.S01E01.mkv")
        val audio = probe(File(workDir, "mkv/Show.S01E01.mkv")).allTracks.filter { it.type == "audio" }
        audio.map { it.trackName } shouldContain "Studio Dub"
    }

    test("with every companion present nothing is skipped").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Show.S01E01.mkv")
        stageInput(workDir, "Show.S01E02.mkv")
        extractTrack(testMkv, File(workDir, "Show.S01E01[Studio].mka"), TrackType.AUDIO, 2)
        extractTrack(testMkv, File(workDir, "Show.S01E02[Studio].mka"), TrackType.AUDIO, 2)
        writeConfig(workDir, companionCfg())

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldNotContain "companion files are missing"
        }
        outputNames(workDir) shouldBe listOf("Show.S01E01.mkv", "Show.S01E02.mkv")
    }

    test("--dry-run reports a missing companion and previews only the complete episode")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Show.S01E01.mkv")
        stageInput(workDir, "Show.S01E02.mkv")
        extractTrack(testMkv, File(workDir, "Show.S01E01[Studio].mka"), TrackType.AUDIO, 2)
        writeConfig(workDir, companionCfg())

        val run = mux(workDir, "--dry-run")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "companion files are missing"
            val lines = run.output.lines()
            lines.count { it == "*** Dry run, would execute:" } shouldBe 1
            val commands = lines.filter { "--output" in it }
            commands shouldHaveSize 1
            commands.single() shouldContain "Show.S01E01.mkv"
            commands.single() shouldNotContain "Show.S01E02"
        }
        File(workDir, "mkv").exists() shouldBe false
    }

    // A bare "Done" after a batch that muxed nothing would read as success.
    test("a batch with every companion missing says there is nothing left to mux")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Show.S01E01.mkv")
        stageInput(workDir, "Show.S01E02.mkv")
        writeConfig(workDir, companionCfg())

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "companion files are missing"
            run.output shouldContain "Nothing left to mux"
        }
        // The pre-flight returns before the destination is created, so an empty batch leaves no litter.
        File(workDir, "mkv").exists() shouldBe false
    }

    test("--strict aborts on a discrepancy that affects a selected track").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "S01E01.mkv"))
        fullCopy(File(workDir, "S01E02.mkv"))
        fullCopy(File(workDir, "odd.mkv"), names = mapOf(2 to "Other Studio"))
        writeConfig(workDir, cfg(audioTracks = audioOneAndTwo))

        val run = mux(workDir, "--strict")

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "affecting selected tracks"
        }
        File(workDir, "mkv").exists() shouldBe false
    }

    test("--strict carries on when the findings are only informational").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "S01E01.mkv"))
        fullCopy(File(workDir, "S01E02.mkv"))
        fullCopy(File(workDir, "odd.mkv"), names = mapOf(2 to "Other Studio"))
        writeConfig(
            workDir,
            cfg(audioTracks = listOf(TrackSpec(id = 1, language = "ja", title = "Japanese", default = true))),
        )

        val run = mux(workDir, "--strict")

        withClue(run.output) { run.exitCode shouldBe 0 }
        outputNames(workDir) shouldBe listOf("S01E01.mkv", "S01E02.mkv", "odd.mkv")
    }

    test("--no-check skips the report and still muxes").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "S01E01.mkv"))
        fullCopy(File(workDir, "odd.mkv"), names = mapOf(2 to "Other Studio"))
        writeConfig(workDir, cfg(audioTracks = audioOneAndTwo))

        val run = mux(workDir, "--no-check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldNotContain "Pre-flight"
        }
        outputNames(workDir) shouldBe listOf("S01E01.mkv", "odd.mkv")
    }

    test("a default run checks first, then muxes").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "S01E01.mkv"))
        fullCopy(File(workDir, "S01E02.mkv"))
        writeConfig(workDir, checkCfg())

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "Pre-flight"
            run.output shouldContain "Processing"
            (run.output.indexOf("Pre-flight") < run.output.indexOf("Processing")) shouldBe true
        }
        outputNames(workDir) shouldBe listOf("S01E01.mkv", "S01E02.mkv")
    }

    // There is no fallback config to load instead: an unrelated one's selections would mux confidently wrong.
    test("a muxing run with no config.yaml is a clean error") {
        val workDir = tempdir()
        stageInput(workDir, "E01.mkv")

        val run = mux(workDir, "--dry-run")

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "No config.yaml"
            run.output shouldContain "config.example.yaml"
            run.output shouldNotContain "Exception"
        }
    }

    // Inspection lives in inspect; mux keeps no aliases for it. picocli refuses the flag before a config is
    // read, so none is staged.
    context("mux refuses the inspection flags") {
        withData(listOf("--identify", "--check", "--check-verbose")) { flag ->
            val run = mux(tempdir(), flag)

            withClue(run.output) {
                run.exitCode shouldBe 2
                run.output shouldContain "Unknown option: '$flag'"
            }
        }
    }

    // mux cannot mux against a config it did not understand, so this is fatal - as a message, not a trace.
    context("a config.yaml mux cannot use is a clean error") {
        withData(
            nameFn = { it.label },
            UnusableFile("a syntax error", "general:\n  destinationDir: mkv\n   bad indent: [\n"),
            UnusableFile("an empty file", ""),
            UnusableFile("a top-level list", "- just\n- a list\n"),
        ) { row ->
            val workDir = tempdir()
            stageInput(workDir, "a.mkv")
            writeConfig(workDir, row.text)

            val run = mux(workDir, "--dry-run")

            withClue(run.output) {
                run.exitCode shouldBe 2
                run.output shouldContain "*** Error:"
                run.output shouldNotContain "Exception"
            }
            File(workDir, "mkv").exists() shouldBe false
        }
    }

    // An environment error: exit 2 before anything is read or written, as a message, not a trace.
    test("a missing mkvmerge on PATH is a clean error").config(enabledOrReasonIf = posixOnly) {
        val workDir = tempdir()
        stageInput(workDir, "a.mkv")
        // Hand-written, because cfg always sets mkvmergeExe and PATH must be the only route here.
        writeConfig(workDir, "general:\n  destinationDir: mkv\nmainSource:\n  audioTracks:\n    - id: 1\n")

        val run = mux(workDir, env = mapOf("PATH" to pathShadowing("mkvmerge", tempdir())))

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "*** Error: 'mkvmerge' not found on PATH or in default install location"
            run.output shouldNotContain "Exception"
        }
        File(workDir, "mkv").exists() shouldBe false
    }

    // A configured path is probed rather than trusted; --no-check would otherwise reach mkvmerge itself, after
    // creating the output directory.
    context("a mkvmergeExe that cannot be run is a clean error in every mode") {
        withData(
            nameFn = { it.first },
            "a checked run" to emptyList(),
            "--no-check" to listOf("--no-check"),
            "--dry-run" to listOf("--dry-run"),
        ) { (_, args) ->
            val workDir = tempdir()
            stageInput(workDir, "a.mkv")
            val missing = File(tempdir(), "mkvmerge").absolutePath
            writeConfig(workDir, cfg(mkvmergeExe = missing))

            val run = mux(workDir, *args.toTypedArray())

            withClue(run.output) {
                run.exitCode shouldBe 2
                run.output shouldContain "*** Error: general.mkvmergeExe '$missing' cannot be run"
                run.output shouldNotContain "Exception"
            }
            File(workDir, "mkv").exists() shouldBe false
        }
    }

    // inspect warns about the same files and carries on; mux would stamp titles from metadata it could not read.
    context("an episodes.yaml mux cannot use is a clean error").config(enabledOrReasonIf = needsMkvmerge) {
        withData(
            nameFn = { it.label },
            UnusableFile("a syntax error", "show: Test\nepisodes:\n  - episode: 1\n   name: [\n"),
            UnusableFile("an empty file", ""),
            UnusableFile("a top-level list", "- just\n- a list\n"),
            UnusableFile(
                "an episode number that is not a number",
                "show: Test\nepisodes:\n  - episode: \"one\"\n    name: Pilot\n",
            ),
        ) { row ->
            val workDir = tempdir()
            stageInput(workDir, "Show - S01E01 - Title.mkv")
            writeConfig(
                workDir,
                cfg(audioTracks = listOf(TrackSpec(id = 1, language = "ja", title = "Japanese", default = true))),
            )
            File(workDir, "episodes.yaml").writeText(row.text, StandardCharsets.UTF_8)

            val run = mux(workDir, "--dry-run")

            withClue(run.output) {
                run.exitCode shouldBe 2
                run.output shouldContain "*** Error:"
                run.output shouldContain "episodes.yaml"
                run.output shouldNotContain "Exception"
            }
            File(workDir, "mkv").exists() shouldBe false
        }
    }
})

/** Audio 1 and 2 selected, so a name split at id 2 affects a selected track. */
private val audioOneAndTwo = listOf(
    TrackSpec(id = 1, language = "ja", title = "Japanese", default = true),
    TrackSpec(id = 2, language = "ru", title = "Russian"),
)

/** A hand-editable file in one of the ways it breaks. */
private class UnusableFile(val label: String, val text: String)

private fun mux(workDir: File, vararg args: String, env: Map<String, String> = emptyMap()): Run =
    mkvtool("mux", *args, workDir = workDir, env = env)
