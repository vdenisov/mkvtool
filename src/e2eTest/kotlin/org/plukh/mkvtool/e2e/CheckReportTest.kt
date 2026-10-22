package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.e2e.support.Run
import org.plukh.mkvtool.e2e.support.TrackSpec
import org.plukh.mkvtool.e2e.support.TrackType
import org.plukh.mkvtool.e2e.support.buildVariant
import org.plukh.mkvtool.e2e.support.cfg
import org.plukh.mkvtool.e2e.support.checkCfg
import org.plukh.mkvtool.e2e.support.fullCopy
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvmerge
import org.plukh.mkvtool.e2e.support.probe
import org.plukh.mkvtool.e2e.support.runMkvmerge
import org.plukh.mkvtool.e2e.support.stageExternalText
import org.plukh.mkvtool.e2e.support.stageExternalTrack
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.stageTree
import org.plukh.mkvtool.e2e.support.testMkv
import org.plukh.mkvtool.e2e.support.writeBytes
import org.plukh.mkvtool.e2e.support.writeChapters
import org.plukh.mkvtool.e2e.support.writeConfig
import java.io.File

/**
 * The consistency report, which one renderer draws for both `inspect` and `mux`'s pre-flight. These cases run
 * it through `inspect`, which writes nothing.
 */
class CheckReportTest : FunSpec({

    val esc = "\u001B"

    test("a clean batch reports consistent, with no deviation markers").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "S01E01.mkv"))
        fullCopy(File(workDir, "S01E02.mkv"))
        writeConfig(workDir, checkCfg())

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "consistent across 2 files"
            run.output shouldNotContain "<-"
        }
        outputDir(workDir).exists() shouldBe false
    }

    test("a track-name split names the minority and not the majority").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "S01E01.mkv"))
        fullCopy(File(workDir, "S01E02.mkv"))
        fullCopy(File(workDir, "odd.mkv"), names = mapOf(2 to "Other Studio"))
        writeConfig(workDir, checkCfg())

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            val arrowLines = run.output.lines().filter { "<-" in it }
            arrowLines.any { "odd.mkv" in it } shouldBe true
            arrowLines.none { "S01E01.mkv" in it || "S01E02.mkv" in it } shouldBe true
            run.output shouldContain "config title \"Russian\""
        }
        outputDir(workDir).exists() shouldBe false
    }

    // The odd file sorts first: anchoring on the first file would flag the three normal ones against it.
    test("grouping anchors on the majority, not the first file").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "a_odd.mkv"), names = mapOf(2 to "Other Studio"))
        listOf("b.mkv", "c.mkv", "d.mkv").forEach { fullCopy(File(workDir, it)) }
        writeConfig(workDir, checkCfg())

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            // Tokenised, because a substring test would find "d.mkv" inside "a_odd.mkv".
            val flagged = run.output.lines().filter { "<-" in it }.flatMap { mkvNames(it) }.toSet()
            flagged shouldContainAll listOf("a_odd.mkv")
            flagged shouldNotContainAnyOf listOf("b.mkv", "c.mkv", "d.mkv")
        }
        outputDir(workDir).exists() shouldBe false
    }

    // A dropped track changes the whole layout, so the file is hoisted into a layout group of its own rather
    // than smeared across the per-id table.
    test("a file missing a track is a layout outlier").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "S01E01.mkv"))
        fullCopy(File(workDir, "S01E02.mkv"))
        buildVariant(File(workDir, "nosub.mkv"), audio = listOf(1, 2, 3), subs = listOf(4, 5))
        writeConfig(workDir, checkCfg())

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "different track layout"
            run.output shouldContain "nosub.mkv"
            run.output shouldContain "selected track 6"
        }
        outputDir(workDir).exists() shouldBe false
    }

    test("a per-file video title is no discrepancy").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "S01E01.mkv"), names = mapOf(0 to "Episode One"))
        fullCopy(File(workDir, "S01E02.mkv"), names = mapOf(0 to "Episode Two"))
        writeConfig(workDir, checkCfg())

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "consistent across 2 files"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // A video row stands for every file in its group, so there is only "the" title when they all agree.
    test("the video title is shown, never compared").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        val build = { name: String, title: String ->
            buildVariant(File(workDir, name), audio = listOf(1), names = mapOf(0 to title))
        }
        build("a.mkv", "Original Japanese")
        build("b.mkv", "Original Japanese")

        val same = inspect(workDir)
        withClue(same.output) {
            same.exitCode shouldBe 0
            same.output shouldContain "Original Japanese"
            same.output shouldContain "consistent"
        }

        build("b.mkv", "Episode Two")
        val run = inspect(workDir)
        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "(per file)"
            // No single file's title is presented as the group's.
            run.output shouldNotContain "Episode Two"
            run.output shouldContain "consistent"
            // "-" is the one glyph for absent, everywhere.
            run.output shouldNotContain "(no name)"
        }
        outputDir(workDir).exists() shouldBe false
    }

    test("chapters present in some files and not others are informational").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "withchap.mkv"), chaptersFile = writeChapters(workDir))
        fullCopy(File(workDir, "nochap.mkv"))
        writeConfig(workDir, checkCfg())

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "Chapters: present in 1 file, absent in 1"
            run.output shouldContain "informational"
        }
        outputDir(workDir).exists() shouldBe false
    }

    test("two indistinguishable same-language tracks are flagged").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        ambiguousPair(File(workDir, "amb.mkv"), secondName = "")
        writeConfig(
            workDir,
            cfg(audioTracks = listOf(TrackSpec(id = 1, language = "en", title = "English", default = true))),
        )

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "Ambiguous track IDs"
            run.output shouldContain "cannot distinguish"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // The false-positive guard: type, language, codec and name must all match.
    test("a name on one of the pair removes the ambiguity").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        val named = File(workDir, "named.mkv")
        ambiguousPair(named, secondName = "Commentary")
        // Absence proves nothing unless the pair really differs only by name.
        probe(named).allTracks.filter { it.type == "audio" }
            .map { it.language to it.trackName.orEmpty() } shouldBe listOf("eng" to "", "eng" to "Commentary")
        writeConfig(
            workDir,
            cfg(audioTracks = listOf(TrackSpec(id = 1, language = "en", title = "English", default = true))),
        )

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldNotContain "Ambiguous track IDs"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // Ids cannot point at the wrong track when the id is not selected, or when every track of the type is taken.
    context("a track-2 name split blocks only when that id is selected and the type is not wholly copied")
        .config(enabledOrReasonIf = needsMkvmerge) {
        withData(
            nameFn = { it.label },
            ValueSplitRow("selected, audio 3 left out", listOf(1, 2), blocking = true),
            ValueSplitRow("not selected", listOf(1), blocking = false),
            ValueSplitRow("every audio track copied", listOf(1, 2, 3), blocking = false),
        ) { row ->
            val workDir = tempdir()
            fullCopy(File(workDir, "S01E01.mkv"))
            fullCopy(File(workDir, "S01E02.mkv"))
            fullCopy(File(workDir, "odd.mkv"), names = mapOf(2 to "Other Studio"))
            writeConfig(
                workDir,
                cfg(audioTracks = row.audio.map { TrackSpec(id = it, language = "ja", title = "Audio $it") }),
            )

            val run = inspect(workDir, "--check")

            withClue(run.output) {
                run.exitCode shouldBe 0
                run.output.contains("the config selects:") shouldBe row.blocking
                run.output.contains("informational") shouldBe !row.blocking
                if (row.blocking) run.output shouldContain "track 2"
            }
            outputDir(workDir).exists() shouldBe false
        }
    }

    test("a file mkvmerge cannot identify is excluded, not crashed on").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "good.mkv"))
        writeBytes(workDir, "broken.mkv", ByteArray(2048))
        writeConfig(workDir, checkCfg())

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "could not be identified"
            run.output shouldContain "broken.mkv"
            run.output shouldNotContain "Exception"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // With no strict majority there is no unnamed reference row, so both value groups list their files.
    test("an even split names every file").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "a.mkv"))
        fullCopy(File(workDir, "b.mkv"))
        fullCopy(File(workDir, "c.mkv"), names = mapOf(2 to "Other"))
        fullCopy(File(workDir, "d.mkv"), names = mapOf(2 to "Other"))
        writeConfig(workDir, checkCfg())

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            // The whole report, not just the "<-" lines: only the last name of each list carries the marker.
            mkvNames(run.output) shouldContainAll listOf("a.mkv", "b.mkv", "c.mkv", "d.mkv")
        }
        outputDir(workDir).exists() shouldBe false
    }

    // Some real episodes carry the subtitle first: compared per id, the same files would show up at three ids.
    test("structurally different files are grouped by layout").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        listOf("a.mkv", "b.mkv", "c.mkv").forEach {
            buildVariant(File(workDir, it), audio = listOf(1), subs = listOf(4))
        }
        listOf("x.mkv", "y.mkv").forEach { shiftedLayout(File(workDir, it)) }
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 1, language = "ja", title = "Japanese", default = true)),
                subtitleTracks = listOf(TrackSpec(id = 2, language = "ja", title = "Signs")),
            ),
        )

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "different track layout"
            // Every group names its files, the largest included; these names carry no episode number.
            mkvNames(run.output) shouldContainAll listOf("a.mkv", "b.mkv", "c.mkv", "x.mkv", "y.mkv")
            run.output shouldContain "Layout 1 (3 files)"
            run.output shouldContain "Layout 2 (2 files)"
            // The shift lands audio and subtitles on ids the config selects.
            run.output shouldContain "a different track layout, at selected track"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // Inspection has no muxing path to fall into: a bare --check-verbose once printed the report and then muxed.
    test("--check-verbose widens the file lists and writes nothing").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        // Letters, not numbers, so the nine members print as a file list rather than as an episode range.
        ('a'..'i').forEach { stageInput(workDir, "$it.mkv") }
        buildVariant(File(workDir, "z.mkv"), audio = listOf(1, 2, 3), subs = listOf(4, 5))
        writeConfig(workDir, checkCfg())

        val plain = inspect(workDir, "--check")
        withClue(plain.output) {
            plain.exitCode shouldBe 0
            plain.output shouldContain "Consistency check"
            plain.output shouldContain "... and 1 more"
            plain.output shouldNotContain "i.mkv"
            plain.output shouldNotContain "Processing"
        }

        val verbose = inspect(workDir, "--check-verbose")
        withClue(verbose.output) {
            verbose.exitCode shouldBe 0
            verbose.output shouldContain "Consistency check"
            verbose.output shouldContain "i.mkv"
            verbose.output shouldNotContain "... and"
            verbose.output shouldNotContain "Processing"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // Check a season before a config exists, then write the config against it.
    test("without a config the differences are counted, not classified").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "a.mkv"))
        fullCopy(File(workDir, "b.mkv"), names = mapOf(2 to "Other Studio"))

        val run = inspect(workDir, "--check")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "Consistency check"
            run.output shouldContain "difference"
            run.output shouldContain "across the batch"
            run.output shouldContain "Add a config"
            run.output shouldNotContain "the config selects"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // Output goes through a pipe, so auto colour is off everywhere else; this is where the palette is asserted.
    test("--color always emits the palette").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "a.mkv"))
        fullCopy(File(workDir, "b.mkv"))
        fullCopy(File(workDir, "odd.mkv"), names = mapOf(2 to "Other Studio"))
        writeConfig(workDir, checkCfg())

        val run = inspect(workDir, "--check", "--color", "always")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "$esc[33m" // differing cells and warnings
            run.output shouldContain "$esc[36m" // headers
            run.output shouldContain "$esc[32m" // the final Done
            run.output shouldContain "$esc[90m" // file-evidence lists
            run.output shouldContain "$esc[0m"
            // Escapes wrap whole lines or cells, so the phrases other cases pin survive colouring.
            run.output shouldContain "*** Consistency check"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // NO_COLOR under auto cannot be told apart from the pipe through a subprocess, so only its precedence is here.
    test("--color never emits nothing, and an explicit always beats NO_COLOR")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        fullCopy(File(workDir, "a.mkv"))
        fullCopy(File(workDir, "odd.mkv"), names = mapOf(2 to "Other Studio"))
        writeConfig(workDir, checkCfg())

        val never = inspect(workDir, "--check", "--color", "never")
        withClue(never.output) {
            never.exitCode shouldBe 0
            never.output shouldNotContain "$esc["
        }

        val always = mkvtool(
            "inspect", "--check", "--color", "always", workDir = workDir, env = mapOf("NO_COLOR" to "1"),
        )
        withClue(always.output) {
            always.exitCode shouldBe 0
            always.output shouldContain "$esc["
        }
        outputDir(workDir).exists() shouldBe false
    }

    // An episode's external files are part of its muxing pass: identical tracks with different dubs are two jobs.
    test("external files are part of the layout, so they split the groups").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageTree(workDir)

        val run = inspect(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            // E01 carries both dub groups and the suffixed sibling, E02 [GroupA] audio and subs, E03 audio only.
            run.output shouldContain "Layout 1"
            run.output shouldContain "Layout 3"
            // The header names external files by label only; the table lists them in full.
            Regex("""Layout 1 [^\n]*\+ [A-Z]""").containsMatchIn(run.output) shouldBe true
            // External rows sit in the table under their label, not under an id they do not have.
            val externalRow = Regex("""^\s+[A-Z]\s+(audio|subs)\s+\S+.*""")
            run.output.lines().any { externalRow.matches(it) } shouldBe true
            // The legend decodes a label into its variant.
            run.output shouldContain "[GroupA]"
            run.output shouldNotContain "External file coverage"
        }
        outputDir(workDir).exists() shouldBe false
    }

    test("a uniform set of external files stays one group").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        listOf("01", "02").forEach { ep ->
            stageInput(workDir, "Show - S01E$ep - Title.mkv")
            stageExternalTrack(workDir, "Rus sound/[Group]/Show - S01E$ep - Title.mka", TrackType.AUDIO, 3)
        }

        val run = inspect(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldNotContain "Layout 2"
            run.output shouldContain "Track structure and external files are consistent"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // The external analogue of a flag flipping mid-season: one dub, tagged Russian for one episode only.
    test("an external value that changes mid-season is reported").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        listOf("01", "02").forEach { stageInput(workDir, "Show - S01E$it - Title.mkv") }
        stageExternalTrack(workDir, "Rus sound/[Group]/Show - S01E01 - Title.mka", TrackType.AUDIO, 3)
        stageExternalTrack(workDir, "Rus sound/[Group]/Show - S01E02 - Title.mka", TrackType.AUDIO, 3, "jpn")
        // A config is what turns findings into labelled items; without one only the count is printed.
        writeConfig(
            workDir,
            cfg(audioTracks = listOf(TrackSpec(id = 1, language = "jpn", title = "JP", default = true))),
        )

        val run = inspect(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldNotContain "Layout 2"
            run.output shouldContain "external A [Group] (audio) - language differs"
            // Nothing selects an external file by id, so an external difference never blocks.
            run.output shouldContain "informational"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // Each group is one muxing pass, so the largest is as much an answer as the others.
    test("every layout group names its episodes, the largest included").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        listOf("01", "02", "03", "04").forEach { ep ->
            stageInput(workDir, "Show - S01E$ep - Title.mkv")
            stageExternalTrack(workDir, "Rus sound/[Both]/Show - S01E$ep - Title.mka", TrackType.AUDIO, 3)
        }
        // Only the first two episodes have the second dub, so the batch splits 2/2.
        listOf("01", "02").forEach { ep ->
            stageExternalTrack(workDir, "Rus sound/[Early]/Show - S01E$ep - Title.mka", TrackType.AUDIO, 2)
        }

        val run = inspect(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "episodes 01-02"
            run.output shouldContain "episodes 03-04"
            val headers = run.output.lines().filter { "*** Layout" in it }
            headers.size shouldBe 2
            headers.all { "episodes " in it } shouldBe true
            // Membership, not deviation, so no "<-" marker.
            headers.none { "<-" in it } shouldBe true
        }
        outputDir(workDir).exists() shouldBe false
    }

    // Keyed on the kind of file alone, the .ass and the .srt would collide in the slot map and E01 and E02 would
    // come out as one pass.
    test("two files of one kind from one variant are two slots").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        listOf("01", "02").forEach { ep ->
            stageInput(workDir, "Show - S01E$ep - Title.mkv")
            stageExternalText(workDir, "Subs/[GroupA]/Show - S01E$ep - Title.ass")
        }
        stageExternalText(workDir, "Subs/[GroupA]/Show - S01E01 - Title.srt")

        val run = inspect(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            // The split is in the slots, not in the discovery.
            run.output shouldContain "1 variant discovered"
            run.output shouldContain "Layout 2"
            run.output shouldNotContain "Layout 3"
        }
        outputDir(workDir).exists() shouldBe false
    }

    // The guessed flag rides beside the signature rather than in it, so it can colour a cell but never split a group.
    test("the check report grays a guessed external language").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        listOf("01", "02").forEach { ep ->
            stageInput(workDir, "Show - S01E$ep - Title.mkv")
            // A probed Japanese track under a folder that would guess Russian: the probe wins.
            stageExternalTrack(workDir, "Rus sound/[Real]/Show - S01E$ep - Title.mka", TrackType.AUDIO, 1)
            // Never probed, so the folder's guess is all it has.
            stageExternalText(workDir, "Rus subs/[Guess]/Show - S01E$ep - Title.ass")
        }

        // One consistent group and no file-evidence lists, so the only gray is the guessed language.
        val colour = inspect(workDir, "--color", "always")
        withClue(colour.output) {
            colour.exitCode shouldBe 0
            colour.output shouldContain "$esc[90mrus? $esc[0m"
            colour.output shouldNotContain "$esc[90mjpn"
        }

        // The "?" is in the text, not the colour, so plain output still marks the guess.
        val plain = inspect(workDir, "--color", "never")
        withClue(plain.output) {
            plain.exitCode shouldBe 0
            plain.output shouldContain "rus?"
            plain.output shouldNotContain "$esc["
        }
        outputDir(workDir).exists() shouldBe false
    }
})

/** Which audio ids a config selects while track 2's name splits the batch, and whether that blocks. */
private class ValueSplitRow(val label: String, val audio: List<Int>, val blocking: Boolean)

private fun inspect(workDir: File, vararg args: String): Run = mkvtool("inspect", *args, workDir = workDir)

private fun outputDir(workDir: File): File = File(workDir, "mkv")

private fun mkvNames(text: String): List<String> = Regex("""[\w.]+\.mkv""").findAll(text).map { it.value }.toList()

/**
 * The video track plus two eng AAC audio tracks muxed from two copies of source track 2, the first unnamed and the
 * second named [secondName]. An empty [secondName] makes them indistinguishable by id selection.
 */
private fun ambiguousPair(dest: File, secondName: String) {
    runMkvmerge(
        "ambiguousPair",
        listOf(
            "--output", dest.absolutePath,
            "--no-subtitles", "--audio-tracks", "2", "--track-name", "2:", testMkv.absolutePath,
            "--no-video", "--no-subtitles", "--audio-tracks", "2", "--track-name", "2:$secondName",
            testMkv.absolutePath,
        ),
    )
}

/** Video, audio 1 and subtitle 4, with the subtitle moved first - a pure track-order shift. */
private fun shiftedLayout(dest: File) {
    runMkvmerge(
        "shiftedLayout",
        listOf(
            "--output", dest.absolutePath,
            "--audio-tracks", "1", "--subtitle-tracks", "4", "--track-order", "0:4,0:0,0:1",
            testMkv.absolutePath,
        ),
    )
}
