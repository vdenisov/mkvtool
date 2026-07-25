package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.plukh.mkvtool.out.Advisory
import java.io.File

/**
 * The three gates between a batch and mkvmerge: stage-two substitution, the companion pre-flight, and the
 * consistency check.
 *
 * They exist because a mux is slow and a failure partway through a long batch is expensive — so what can
 * be known up front is worked out up front. What separates them is what they do about what they find, and
 * that is what these cases pin: stage two drops episodes (and aborts under `--strict`), the companion
 * check drops episodes and *never* aborts, and the consistency check only reports unless `--strict` says
 * otherwise.
 */
class MuxPreflightTest : FunSpec({

    context("stage two: variables with no value for one episode") {
        test("an episode with no number drops, and the rest of the batch still muxes") {
            val dir = tempdir()
            listOf("My Show - S01E01 - One.mkv", "NoEpisodeNumberHere.mkv").forEach {
                File(dir, it).writeText("x")
            }

            val run = mux(dir, RecordingRenderer(), usedFileVars = setOf("episodeNum", "episodeName"))

            val drops = run.substitutionDrops!!
            drops.fileNames shouldContainExactly listOf("NoEpisodeNumberHere.mkv")
            // Grouped by variable, because that is the shape of the fix: one missing ${episodeNum} across
            // eleven files is one thing to go and correct.
            drops.variables.map { it.name } shouldContainExactly listOf("episodeNum", "episodeName")
            drops.variables.forEach { it.fileNames shouldContainExactly listOf("NoEpisodeNumberHere.mkv") }
            run.files.map { it.fileName } shouldContainExactly listOf("My Show - S01E01 - One.mkv")
        }

        test("only the variables the config actually uses can drop anything") {
            val dir = tempdir()
            File(dir, "NoEpisodeNumberHere.mkv").writeText("x")

            // The file resolves no episode number, but nothing asked for one.
            val run = mux(dir, RecordingRenderer(), usedFileVars = setOf("fileName"))

            run.substitutionDrops.shouldBeNull()
            run.files.map { it.fileName } shouldContainExactly listOf("NoEpisodeNumberHere.mkv")
        }

        test("--strict turns the same finding into an abort, before anything is probed") {
            val dir = tempdir()
            listOf("My Show - S01E01 - One.mkv", "NoEpisodeNumberHere.mkv").forEach {
                File(dir, it).writeText("x")
            }

            val run = mux(
                dir, RecordingRenderer(),
                usedFileVars = setOf("episodeNum"), strict = true,
                probe = { error("strict must abort before the check probes anything") },
            )

            run.aborted shouldBe MuxAbort.UnresolvedVariables(1)
            run.files.shouldBeEmpty()
            // Before the mkdirs, so a refused batch leaves nothing behind.
            File(dir, "mkv").exists() shouldBe false
        }

        test("every episode dropped says so rather than ending on a bare Done") {
            val dir = tempdir()
            File(dir, "NoEpisodeNumberHere.mkv").writeText("x")
            val renderer = RecordingRenderer()

            val run = mux(dir, renderer, usedFileVars = setOf("episodeNum"))

            run.files.shouldBeEmpty()
            renderer.events.filterIsInstance<Advisory>().map { it.text } shouldContainExactly
                listOf("*** Nothing left to mux")
            File(dir, "mkv").exists() shouldBe false
        }
    }

    context("the companion pre-flight") {
        test("an episode whose companion is absent drops, and the complete one still muxes") {
            val dir = tempdir()
            listOf("Show.S01E01.mkv", "Show.S01E02.mkv").forEach { File(dir, it).writeText("x") }
            File(dir, "Show.S01E01[Studio].mka").writeText("x")

            val run = mux(dir, RecordingRenderer(), sources = listOf("\${fileName}[Studio].mka"))

            val drops = run.companionDrops!!
            drops.fileNames shouldContainExactly listOf("Show.S01E02.mkv")
            // Named by the pattern, unresolved: that is the line in the config to go and look at.
            drops.sources.single().pattern shouldBe "\${fileName}[Studio].mka"
            run.muxedNames shouldContainExactly listOf("Show.S01E01.mkv")
        }

        test("every companion present is silence, not an empty report") {
            val dir = tempdir()
            File(dir, "Show.S01E01.mkv").writeText("x")
            File(dir, "Show.S01E01[Studio].mka").writeText("x")

            mux(dir, RecordingRenderer(), sources = listOf("\${fileName}[Studio].mka"))
                .companionDrops.shouldBeNull()
        }

        test("--strict does not abort here: a partially released dub is an ordinary situation") {
            val dir = tempdir()
            listOf("Show.S01E01.mkv", "Show.S01E02.mkv").forEach { File(dir, it).writeText("x") }
            File(dir, "Show.S01E01[Studio].mka").writeText("x")

            val run = mux(
                dir, RecordingRenderer(),
                sources = listOf("\${fileName}[Studio].mka"), strict = true,
            )

            run.aborted.shouldBeNull()
            run.muxedNames shouldContainExactly listOf("Show.S01E01.mkv")
        }

        test("a companion missing for every episode empties the batch and says so") {
            val dir = tempdir()
            listOf("Show.S01E01.mkv", "Show.S01E02.mkv").forEach { File(dir, it).writeText("x") }
            val renderer = RecordingRenderer()

            val run = mux(dir, renderer, sources = listOf("\${fileName}[Studio].mka"))

            run.files.shouldBeEmpty()
            renderer.events.filterIsInstance<Advisory>().map { it.text } shouldContainExactly
                listOf("*** Nothing left to mux")
            File(dir, "mkv").exists() shouldBe false
        }
    }

    context("the pre-flight check") {
        test("runs by default, over the batch the drops left") {
            val dir = tempdir()
            listOf("a.mkv", "b.mkv").forEach { File(dir, it).writeText("x") }
            val renderer = RecordingRenderer()

            val run = mux(dir, renderer, probe = { probedOk(it) })

            run.check!!.headerLabel shouldBe "Pre-flight check"
            run.check!!.readable shouldContainExactly listOf("a.mkv", "b.mkv")
            // The meter's total is the work that actually ran, so nothing continues after it finishes.
            renderer.progressTotal shouldBe 2
            renderer.ticks shouldBe 2
        }

        test("--no-check skips it, and the batch still muxes") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")

            val run = mux(dir, RecordingRenderer(), check = false, probe = { error("must not probe") })

            run.check.shouldBeNull()
            run.muxed shouldBe 1
        }

        test("its probes are the ones \${codec} reads, so a file is read once for both") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")
            var probes = 0

            mux(
                dir, RecordingRenderer(),
                usesCodec = true,
                audioTitle = Template("\${codec}"),
                probe = { probes++; probedOk(it) },
            )

            // v1 shared one `probedInfos` map between the pre-flight and the codec lookup, and on a network
            // share reading a season twice is the difference that matters.
            probes shouldBe 1
        }

        test("a blocking discrepancy is reported and muxed anyway, unless --strict") {
            val dir = tempdir()
            listOf("a.mkv", "b.mkv").forEach { File(dir, it).writeText("x") }
            val probe = { file: File ->
                // b.mkv carries a different name at track 1, which the config selects by id.
                probedOk(file, audioName = if (file.name == "b.mkv") "Other Studio" else "Audio A")
            }

            val lenient = mux(dir, RecordingRenderer(), probe = probe)
            lenient.check!!.blockingCount shouldBe 1
            lenient.aborted.shouldBeNull()
            lenient.muxed shouldBe 2

            val strictDir = tempdir()
            listOf("a.mkv", "b.mkv").forEach { File(strictDir, it).writeText("x") }
            val strict = mux(strictDir, RecordingRenderer(), strict = true, probe = probe)
            strict.aborted.shouldBeInstanceOf<MuxAbort.BlockingDiscrepancies>()
            strict.files.shouldBeEmpty()
            // The report is still part of the document: the abort says how many, not which.
            strict.check!!.blockingCount shouldBe 1
            File(strictDir, "mkv").exists() shouldBe false
        }

        test("an informational discrepancy never aborts, however strict the run") {
            val dir = tempdir()
            listOf("a.mkv", "b.mkv").forEach { File(dir, it).writeText("x") }

            // Track 3 is not selected by the config, so a difference there cannot change what is muxed.
            val run = mux(
                dir, RecordingRenderer(), strict = true,
                probe = { file -> probedOk(file, spareName = if (file.name == "b.mkv") "Other" else "Spare") },
            )

            run.check!!.findings.isNotEmpty() shouldBe true
            run.check!!.blockingCount shouldBe 0
            run.aborted.shouldBeNull()
            run.muxed shouldBe 2
        }
    }

    test("the gates run in order, and each one narrows what the next sees") {
        val dir = tempdir()
        listOf("Show.S01E01.mkv", "Show.S01E02.mkv", "NoEpisodeNumberHere.mkv").forEach {
            File(dir, it).writeText("x")
        }
        File(dir, "Show.S01E01[Studio].mka").writeText("x")
        val probed = mutableListOf<String>()

        val run = mux(
            dir, RecordingRenderer(),
            usedFileVars = setOf("episodeNum"),
            sources = listOf("\${fileName}[Studio].mka"),
            probe = { probed += it.name; probedOk(it) },
        )

        // Stage two drops the unnumbered file, the companion check drops E02, and only E01 is left to be
        // probed at all — the later gates never see what an earlier one removed.
        run.substitutionDrops!!.fileNames shouldContainExactly listOf("NoEpisodeNumberHere.mkv")
        run.companionDrops!!.fileNames shouldContainExactly listOf("Show.S01E02.mkv")
        probed shouldContainExactly listOf("Show.S01E01.mkv")
        run.muxedNames shouldContainExactly listOf("Show.S01E01.mkv")
    }
})

/** The files that were actually muxed. A companion sharing the directory is not media and rides along in
 *  [MuxRun.files] as a skipped entry, which is what the batch reports about it. */
private val MuxRun.muxedNames: List<String>
    get() = files.filter { it.outcome !is MuxOutcome.Skipped }.map { it.fileName }

/** A readable probe result: video, the audio track the config selects, and one it does not. */
private fun probedOk(file: File, audioName: String = "Audio A", spareName: String = "Spare"): ProbeResult {
    val tracks = listOf(
        ProbedTrack(0, "video", "AVC", "V_MPEG4/ISO/AVC", "und", "Video", true, false),
        ProbedTrack(1, "audio", "AAC", "A_AAC", "jpn", audioName, true, false),
        ProbedTrack(3, "audio", "AAC", "A_AAC", "rus", spareName, false, false),
    )
    return ProbeResult.Probed(
        file = file,
        allTracks = tracks,
        tracks = tracks.associate {
            it.id to TrackSlot(it.id, signatureOf(it), if (it.type == "video") it.trackName else null)
        },
        chapters = 0,
    )
}

private fun mux(
    dir: File,
    renderer: RecordingRenderer,
    usedFileVars: Set<String> = emptySet(),
    sources: List<String> = emptyList(),
    strict: Boolean = false,
    check: Boolean = true,
    usesCodec: Boolean = false,
    audioTitle: Template = Template("Japanese"),
    probe: (File) -> ProbeResult = { probedOk(it) },
): MuxRun = muxDirectory(
    dir = dir,
    options = MuxOptions(
        config = Config(
            general = GeneralConfig(
                destinationDir = "mkv",
                allowedExtensions = setOf("mkv"),
                mkvmergeExe = "mkvmerge",
                title = Template("\${episodeName}"),
            ),
            mainSource = MainSourceConfig(
                videoTrack = VideoTrackConfig(language = "ja"),
                audioTracks = listOf(TrackConfig(id = 1, language = "ja", title = audioTitle)),
            ),
            additionalSources = sources.map { AdditionalSource(file = it) },
        ),
        mkvmergeExe = "mkvmerge",
        uiLanguage = "en",
        trackOrder = "0:0,0:1",
        usesCodec = usesCodec,
        usedFileVars = usedFileVars,
        episodeSource = null,
        check = check,
        strict = strict,
    ),
    renderer = renderer,
    probe = probe,
    runCommand = { _, _ -> 0 },
)
