package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.plukh.mkvtool.out.Advisory
import org.plukh.mkvtool.out.Header
import java.io.File

/**
 * The batch: which files are walked, in what order, and what each one's outcome is.
 *
 * mkvmerge is injected, so this runs in-process — what it pins is the orchestration around the command
 * line, which is where the batch's promises live. The important one is that **nothing aborts**: a season
 * where one episode fails is a normal outcome, and the other twenty-three are worth muxing.
 */
class MuxDirectoryTest : FunSpec({

    test("every media file is muxed, in name order, and reported as it completes") {
        val dir = tempdir()
        listOf("c.mkv", "a.mkv", "b.mkv").forEach { File(dir, it).writeText("x") }
        val renderer = RecordingRenderer()

        val run = mux(dir, renderer)

        run.files.map { it.fileName } shouldContainExactly listOf("a.mkv", "b.mkv", "c.mkv")
        run.muxed shouldBe 3
        // Each file's header comes before its own result and after the previous one's, which is what makes
        // mkvmerge's own output land under the right name.
        renderer.events.filterIsInstance<Header>().map { it.text } shouldContainExactly
            listOf("*** Processing a.mkv", "*** Processing b.mkv", "*** Processing c.mkv")
        renderer.results.map { (it as FileMux).fileName } shouldContainExactly listOf("a.mkv", "b.mkv", "c.mkv")
    }

    test("a file that is not media is named and passed over, with nothing built for it") {
        val dir = tempdir()
        File(dir, "a.mkv").writeText("x")
        File(dir, "notes.txt").writeText("x")

        val run = mux(dir, RecordingRenderer())

        val skipped = run.files.single { it.fileName == "notes.txt" }
        skipped.outcome shouldBe MuxOutcome.Skipped
        skipped.command shouldBe null
        skipped.outputPath shouldBe null
        run.skipped shouldBe 1
    }

    test("a non-zero exit is that file's failure and nothing else's") {
        val dir = tempdir()
        listOf("a.mkv", "b.mkv").forEach { File(dir, it).writeText("x") }

        val run = mux(dir, RecordingRenderer(), exitCode = { if (it.contains("a.mkv")) 2 else 0 })

        run.files.single { it.fileName == "a.mkv" }.outcome shouldBe MuxOutcome.Failed(2)
        run.files.single { it.fileName == "b.mkv" }.outcome shouldBe MuxOutcome.Muxed
        run.failed shouldBe 1
        run.muxed shouldBe 1
    }

    test("mkvmerge is run in the media directory, since the command names its source by bare name") {
        val dir = tempdir()
        File(dir, "a.mkv").writeText("x")
        var ranIn: File? = null

        mux(dir, RecordingRenderer(), record = { _, where -> ranIn = where })

        ranIn shouldBe dir
    }

    context("--dry-run") {
        test("builds and reports the command without running anything or creating the output directory") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")
            var ran = false

            val run = mux(dir, RecordingRenderer(), dryRun = true, record = { _, _ -> ran = true })

            ran shouldBe false
            File(dir, "mkv").exists() shouldBe false
            run.files.single().outcome shouldBe MuxOutcome.Previewed
            run.files.single().command!!.first() shouldBe "mkvmerge"
        }

        test("a real run creates the output directory before the first file") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")

            mux(dir, RecordingRenderer())

            File(dir, "mkv").isDirectory shouldBe true
        }
    }

    context("an empty batch says why, and leaves nothing behind") {
        test("a mask matching no file at all names the patterns") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")
            val renderer = RecordingRenderer()

            val run = mux(dir, renderer, fileMasks = listOf("Nope.*.mkv"))

            run.files.shouldBeEmpty()
            advisory(renderer) shouldBe "*** No files match: Nope.*.mkv"
            // Before the mkdirs, so a typo'd pattern leaves no empty output directory behind.
            File(dir, "mkv").exists() shouldBe false
        }

        test("a mask matching only non-media is a different mistake, and says so") {
            val dir = tempdir()
            File(dir, "notes.txt").writeText("x")
            val renderer = RecordingRenderer()

            mux(dir, renderer, fileMasks = listOf("notes.txt"))

            advisory(renderer) shouldBe "*** No media files match: notes.txt"
        }

        test("with no masks at all, the extensions it looked for are what is worth naming") {
            val renderer = RecordingRenderer()

            mux(tempdir(), renderer)

            advisory(renderer) shouldBe "*** No media files (avi, mkv, mp4) in the current directory"
        }

        test("an exclude mask is named with its own flag") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")
            val renderer = RecordingRenderer()

            mux(dir, renderer, excludeMasks = listOf("*.mkv"))

            advisory(renderer) shouldBe "*** No files match: --exclude *.mkv"
        }
    }

    test("the output path is the destination directory plus the base name, always with a forward slash") {
        val dir = tempdir()
        File(dir, "Show - S01E01.mkv").writeText("x")

        mux(dir, RecordingRenderer()).files.single().outputPath shouldBe "mkv/Show - S01E01.mkv"
    }

    test("the run root is returned and never emitted — a mux ends on a bare Done") {
        val dir = tempdir()
        File(dir, "a.mkv").writeText("x")
        val renderer = RecordingRenderer()

        val run = mux(dir, renderer)

        run.shouldBeInstanceOf<MuxRun>()
        renderer.results.none { it is MuxRun } shouldBe true
    }
})

private fun advisory(renderer: RecordingRenderer): String =
    renderer.events.filterIsInstance<Advisory>().single().text

private fun mux(
    dir: File,
    renderer: RecordingRenderer,
    dryRun: Boolean = false,
    fileMasks: List<String> = emptyList(),
    excludeMasks: List<String> = emptyList(),
    exitCode: (List<String>) -> Int = { 0 },
    record: (List<String>, File) -> Unit = { _, _ -> },
): MuxRun = muxDirectory(
    dir = dir,
    options = MuxOptions(
        config = Config(
            general = GeneralConfig(
                destinationDir = "mkv",
                allowedExtensions = setOf("mkv", "mp4", "avi"),
                mkvmergeExe = "mkvmerge",
            ),
            mainSource = MainSourceConfig(
                videoTrack = VideoTrackConfig(language = "ja"),
                audioTracks = listOf(TrackConfig(id = 1, language = "ja", title = Template("Japanese"))),
            ),
        ),
        mkvmergeExe = "mkvmerge",
        uiLanguage = "en",
        trackOrder = "0:0,0:1",
        dryRun = dryRun,
        fileMasks = fileMasks,
        excludeMasks = excludeMasks,
    ),
    renderer = renderer,
    probe = { error("no probe expected without \${codec}") },
    runCommand = { command, where -> record(command, where); exitCode(command) },
)
