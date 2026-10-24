package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * `mux` over a config that binds: the episodes binding reads, the companions it pairs, what it skips and
 * refuses, and the output names. Plus the collision check, which every run gets, binding or not.
 *
 * mkvmerge is injected and the check is off, as in `MuxDirectoryTest`, so what is pinned is the batch and
 * the command lines.
 */
class MuxBindingTest : FunSpec({

    test("a raw release binds by its regex, through the map, and is named by outputName made file-safe") {
        val dir = tempdir()
        touch(dir, "[Grp] Show - 13 (BD).mkv", "[Grp] Show - 14 (BD).mkv")
        val config = config(
            matching = MatcherConfig(
                episodeRegex = """ - (\d+) \(""",
                episodeMap = EpisodeMap(listOf(EpisodeMapEntry("13-14", "1-2"))),
            ),
            outputName = "Show - S\${seasonNum}E\${episodeNum} - \${episodeName}",
        )
        val episodes = EpisodeData(season = "01", byEpisode = mapOf("01" to "One: Start", "02" to "Two?"))

        val run = mux(dir, config, episodes, usedFileVars = setOf("seasonNum", "episodeNum", "episodeName"))

        run.files.map { it.outputPath } shouldContainExactly
            listOf("mkv/Show - S01E01 - One Start.mkv", "mkv/Show - S01E02 - Two.mkv")
        run.files.first().command!! shouldContainInOrder listOf("--output", "mkv/Show - S01E01 - One Start.mkv")
    }

    test("a files: source's paired file goes on the command line, and a VobSub pair as its .idx alone") {
        val dir = tempdir()
        touch(dir, "Show - S01E01.mkv", "Show - S01E02.mkv")
        touch(dir, "Subs/Show - S01E01.idx", "Subs/Show - S01E01.sub", "Subs/Show - S01E02.srt")
        val config = config(sources = listOf(source("Subs/*")))

        val run = mux(dir, config)

        val (first, second) = run.files.map { it.command!! }
        first shouldContainInOrder listOf("(", "Subs/Show - S01E01.idx", ")")
        first.none { it.endsWith(".sub") } shouldBe true
        second shouldContainInOrder listOf("(", "Subs/Show - S01E02.srt", ")")
    }

    test("a main no rule reads is warned about and never muxed") {
        val dir = tempdir()
        touch(dir, "Show E01.mkv", "NCOP.mkv")
        var ran = emptyList<String>()

        val run = mux(
            dir, config(matching = MatcherConfig(episodeRegex = """E(\d+)""")), EpisodeData(season = "01"),
            record = { ran = ran + it.joinToString(" ") },
        )

        run.files.map { it.fileName } shouldContainExactly listOf("Show E01.mkv")
        run.binding!!.skipped shouldContainExactly listOf(File(dir, "NCOP.mkv"))
        ran.single() shouldNotContain "NCOP"
    }

    test("a skipped main the masks left out of the run is not warned about") {
        val dir = tempdir()
        touch(dir, "Show E01.mkv", "Show E02.mkv", "NCOP.mkv")
        val renderer = RecordingRenderer()

        mux(
            dir, config(matching = MatcherConfig(episodeRegex = """E(\d+)""")), EpisodeData(season = "01"),
            renderer = renderer, fileMasks = listOf("Show E01.mkv"),
        )

        // The report covers the run, not the directory: the episode the mask left out is not in it either.
        val reported = renderer.results.filterIsInstance<BindingReport>().single().binding
        reported.skipped.shouldBeEmpty()
        reported.mains.map { it.main.name } shouldContainExactly listOf("Show E01.mkv")
    }

    test("a binding problem stops the batch before anything is written, whatever --strict says") {
        val dir = tempdir()
        touch(dir, "A E01.mkv", "B E01.mkv")
        var ran = false
        val renderer = RecordingRenderer()

        val run = mux(
            dir, config(matching = MatcherConfig(episodeRegex = """E(\d+)""")), EpisodeData(season = "01"),
            renderer = renderer, record = { ran = true },
        )

        run.aborted shouldBe MuxAbort.Unbound(1)
        run.binding!!.problems.single().shouldBeInstanceOf<DuplicateEpisode>()
        run.files.shouldBeEmpty()
        ran shouldBe false
        File(dir, "mkv").exists() shouldBe false
        // The problems first, then what they cost.
        renderer.results.map { it::class } shouldContainExactly listOf(BindingReport::class, MuxAbort.Unbound::class)
        renderer.results.filterIsInstance<BindingReport>().single().fatal shouldBe true
    }

    test("a main its files: source paired nothing with is dropped like a missing companion, and the rest muxes") {
        val dir = tempdir()
        touch(dir, "Show - S01E01.mkv", "Show - S01E02.mkv", "Subs/Show - S01E01.srt")

        val run = mux(dir, config(sources = listOf(source("Subs/*.srt"))))

        val drops = run.companionDrops!!
        drops.fileNames shouldContainExactly listOf("Show - S01E02.mkv")
        drops.sources.single().pattern shouldBe "additionalSources[0].files: Subs/*.srt"
        run.files.map { it.fileName } shouldContainExactly listOf("Show - S01E01.mkv")
    }

    context("two files resolving to one output stop the batch, binding or not") {
        test("an outputName that names no episode") {
            val dir = tempdir()
            touch(dir, "a.mkv", "b.mkv")
            var ran = false

            val run = mux(dir, config(outputName = "Show"), record = { ran = true })

            run.aborted shouldBe MuxAbort.OutputCollisions(mapOf("mkv/Show.mkv" to listOf("a.mkv", "b.mkv")))
            ran shouldBe false
            File(dir, "mkv").exists() shouldBe false
        }

        test("a v1 config over two main files sharing a base name") {
            val dir = tempdir()
            touch(dir, "a.mkv", "a.mp4")

            val run = mux(dir, config())

            run.aborted shouldBe MuxAbort.OutputCollisions(mapOf("mkv/a.mkv" to listOf("a.mkv", "a.mp4")))
        }

        test("two output names that differ only in case, on every platform") {
            val dir = tempdir()
            touch(dir, "a.mkv", "A.mp4")

            val run = mux(dir, config())

            run.aborted shouldBe MuxAbort.OutputCollisions(mapOf("mkv/A.mkv" to listOf("A.mp4", "a.mkv")))
        }
    }
})

private infix fun List<String>.shouldContainInOrder(run: List<String>) {
    windowed(run.size).any { it == run } shouldBe true
}

private fun touch(dir: File, vararg paths: String) = paths.forEach {
    File(dir, it).apply { parentFile.mkdirs() }.writeText("x")
}

private fun source(vararg masks: String) =
    AdditionalSource(files = masks.toList(), tracks = listOf(SourceTrackConfig(language = "ru")))

private fun config(
    matching: MatcherConfig = MatcherConfig(),
    outputName: String? = null,
    sources: List<AdditionalSource> = emptyList(),
) = Config(
    general = GeneralConfig(
        destinationDir = "mkv",
        allowedExtensions = setOf("mkv", "mp4"),
        mkvmergeExe = "mkvmerge",
        matching = matching,
        outputName = outputName?.let(::Template),
    ),
    mainSource = MainSourceConfig(videoTrack = VideoTrackConfig(language = "ja")),
    additionalSources = sources,
)

private fun mux(
    dir: File,
    config: Config,
    episodes: EpisodeData? = null,
    usedFileVars: Set<String> = emptySet(),
    renderer: RecordingRenderer = RecordingRenderer(),
    record: (List<String>) -> Unit = {},
    fileMasks: List<String> = emptyList(),
): MuxRun = muxDirectory(
    dir = dir,
    options = MuxOptions(
        config = config,
        mkvmergeExe = "mkvmerge",
        uiLanguage = null,
        trackOrder = deriveTrackOrder(config),
        substitution = SubstitutionEngine(episodes),
        usedFileVars = usedFileVars,
        check = false,
        fileMasks = fileMasks,
    ),
    renderer = renderer,
    probe = { error("no probe expected: the check is off and no template asks for a codec") },
    runCommand = { command, _ -> record(command); 0 },
)
