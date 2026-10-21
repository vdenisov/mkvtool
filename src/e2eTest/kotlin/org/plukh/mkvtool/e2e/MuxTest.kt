package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.plukh.mkvtool.core.ProbedTrack
import org.plukh.mkvtool.e2e.support.AdditionalSourceSpec
import org.plukh.mkvtool.e2e.support.Run
import org.plukh.mkvtool.e2e.support.Smoke
import org.plukh.mkvtool.e2e.support.SourceTrackSpec
import org.plukh.mkvtool.e2e.support.TrackSpec
import org.plukh.mkvtool.e2e.support.TrackType
import org.plukh.mkvtool.e2e.support.cfg
import org.plukh.mkvtool.e2e.support.extractTrack
import org.plukh.mkvtool.e2e.support.findOutput
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvmerge
import org.plukh.mkvtool.e2e.support.outputNames
import org.plukh.mkvtool.e2e.support.probe
import org.plukh.mkvtool.e2e.support.stageBatch
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.testMkv
import org.plukh.mkvtool.e2e.support.writeConfig
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * `mux` end to end: a real config, a real mkvmerge invocation, and the output read back through the
 * production probe parser.
 *
 * The input fixture `test.mkv` carries track 0 video (und), 1-3 audio (jpn/eng/rus) and 4-6 subtitles
 * (eng/rus-forced/jpn); every id in these configs indexes into that.
 */
class MuxTest : FunSpec({

    test("one audio and one subtitle are selected out of the source")
        .config(tags = setOf(Smoke), enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                subtitleTracks = listOf(TrackSpec(id = 4, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2,0:4",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        val tracks = muxedTracks(workDir)
        tracks.ofType("video").size shouldBe 1
        tracks.ofType("audio").map { it.language } shouldBe listOf("eng")
        tracks.ofType("subtitles").map { it.language } shouldBe listOf("eng")
    }

    test("two audio tracks keep their own default flags").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(
                    TrackSpec(id = 1, language = "ja", title = "Japanese", default = true),
                    TrackSpec(id = 2, language = "en", title = "English"),
                ),
                trackOrder = "0:0,0:1,0:2",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        val audio = muxedTracks(workDir).ofType("audio")
        audio.size shouldBe 2
        audio[0].language shouldBe "jpn"
        audio[0].default shouldBe true
        audio[1].default shouldBe false
    }

    // Both spellings mean "copy none of this type"; the builder keeps them apart so each is pinned.
    context("an absent and an empty track list both copy none of that type")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val audio = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true))
        val subs = listOf(TrackSpec(id = 4, language = "en", title = "English", default = true))
        withData(
            nameFn = { it.label },
            ListRow("audioTracks absent", null, subs, "0:0,0:4", emptied = "audio", kept = "subtitles"),
            ListRow("audioTracks empty", emptyList(), subs, "0:0,0:4", emptied = "audio", kept = "subtitles"),
            ListRow("subtitleTracks absent", audio, null, "0:0,0:2", emptied = "subtitles", kept = "audio"),
            ListRow("subtitleTracks empty", audio, emptyList(), "0:0,0:2", emptied = "subtitles", kept = "audio"),
        ) { row ->
            val workDir = tempdir()
            stageInput(workDir)
            writeConfig(
                workDir,
                cfg(audioTracks = row.audio, subtitleTracks = row.subs, trackOrder = row.trackOrder),
            )

            mux(workDir).exitCode shouldBe 0

            val tracks = muxedTracks(workDir)
            tracks.ofType(row.emptied).size shouldBe 0
            tracks.ofType(row.kept).size shouldBe 1
        }
    }

    test("the video language is overridden").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                videoLang = "ja",
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        muxedTracks(workDir).single { it.type == "video" }.language shouldBe "jpn"
    }

    test("the segment and video titles default to the file name").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "MyShow.S01E01.mkv")
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        val probed = probe(findOutput(workDir).shouldNotBeNull())
        probed.containerTitle shouldBe "MyShow.S01E01"
        probed.allTracks.single { it.type == "video" }.trackName shouldBe "MyShow.S01E01"
    }

    // The two titles default independently, so overriding one must leave the other on the file name.
    test("a video title override leaves the segment title alone").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                videoTitle = "Custom Title",
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        val probed = probe(findOutput(workDir).shouldNotBeNull())
        probed.allTracks.single { it.type == "video" }.trackName shouldBe "Custom Title"
        probed.containerTitle shouldBe "test"
    }

    test("three audio tracks carry the default flag only where configured")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(
                    TrackSpec(id = 1, language = "ja", title = "Japanese"),
                    TrackSpec(id = 2, language = "en", title = "English", default = true),
                    TrackSpec(id = 3, language = "ru", title = "Russian"),
                ),
                trackOrder = "0:0,0:1,0:2,0:3",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        muxedTracks(workDir).ofType("audio").map { it.default } shouldBe listOf(false, true, false)
    }

    test("a subtitle with no charset muxes").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                subtitleTracks = listOf(TrackSpec(id = 4, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2,0:4",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        muxedTracks(workDir).ofType("subtitles").size shouldBe 1
    }

    // The source is already UTF-8, so the charset changes nothing visible: what this pins is that it is
    // accepted and the track still muxes.
    test("a subtitle with an explicit charset muxes").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                subtitleTracks = listOf(TrackSpec(id = 5, language = "ru", title = "Russian", charset = "UTF-8")),
                trackOrder = "0:0,0:2,0:5",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        muxedTracks(workDir).ofType("subtitles").map { it.language } shouldBe listOf("rus")
    }

    test("a forced subtitle stays forced").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                subtitleTracks = listOf(TrackSpec(id = 5, language = "ru", title = "Forced")),
                trackOrder = "0:0,0:2,0:5",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        muxedTracks(workDir).ofType("subtitles").single().forced shouldBe true
    }

    test("trackOrder reorders the audio").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(
                    TrackSpec(id = 1, language = "ja", title = "Japanese"),
                    TrackSpec(id = 2, language = "en", title = "English", default = true),
                ),
                trackOrder = "0:0,0:2,0:1",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        muxedTracks(workDir).ofType("audio").map { it.language } shouldBe listOf("eng", "jpn")
    }

    // mkvmerge silently drops --track-order entries that match no muxed track, so the warning is the only
    // sign a stale id was left behind.
    test("a trackOrder id matching no configured track is warned about, and the file still muxes")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2,0:999",
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "trackOrder references track IDs not configured: 0:999"
            run.output shouldContain "*** Done"
        }
        muxedTracks(workDir).ofType("audio").size shouldBe 1
    }

    test("an omitted trackOrder is derived from the configured tracks").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(
                    TrackSpec(id = 1, language = "ja", title = "Japanese", default = true),
                    TrackSpec(id = 2, language = "en", title = "English"),
                ),
                subtitleTracks = listOf(TrackSpec(id = 4, language = "en", title = "English", default = true)),
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "using derived order: 0:0,0:1,0:2,0:4"
        }
        val tracks = muxedTracks(workDir)
        tracks.first().type shouldBe "video"
        tracks.ofType("audio").map { it.language } shouldBe listOf("jpn", "eng")
        tracks.ofType("subtitles").size shouldBe 1
    }

    test("a configured track left out of trackOrder is warned about, and still muxed")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(
                    TrackSpec(id = 1, language = "ja", title = "Japanese", default = true),
                    TrackSpec(id = 2, language = "en", title = "English"),
                ),
                trackOrder = "0:0,0:1",
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "trackOrder omits configured track IDs: 0:2"
        }
        muxedTracks(workDir).ofType("audio").size shouldBe 2
    }

    test("an external audio companion is muxed in by \${fileName}").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        extractTrack(testMkv, File(workDir, "test[Extra].mka"), TrackType.AUDIO, 2)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 1, language = "ja", title = "Japanese", default = true)),
                trackOrder = "0:0,0:1,1:0",
                additionalSources = listOf(
                    AdditionalSourceSpec(
                        file = "\${fileName}[Extra].mka",
                        tracks = listOf(SourceTrackSpec(language = "en", title = "Extra English")),
                    ),
                ),
            ),
        )

        mux(workDir).exitCode shouldBe 0

        val audio = muxedTracks(workDir).ofType("audio")
        audio.size shouldBe 2
        audio.map { it.trackName } shouldContain "Extra English"
    }

    // As with the main source, the charset is accepted rather than observable: the extracted SRT is UTF-8.
    test("an external SRT with a charset is muxed in").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        extractTrack(testMkv, File(workDir, "test.srt"), TrackType.SUBTITLES, 4)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2,1:0",
                additionalSources = listOf(
                    AdditionalSourceSpec(
                        file = "\${fileName}.srt",
                        tracks = listOf(SourceTrackSpec(language = "en", title = "ExtSub", charset = "UTF-8")),
                    ),
                ),
            ),
        )

        mux(workDir).exitCode shouldBe 0

        muxedTracks(workDir).ofType("subtitles").map { it.trackName } shouldBe listOf("ExtSub")
    }

    test("an external SRT with no charset is muxed in").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        extractTrack(testMkv, File(workDir, "test.srt"), TrackType.SUBTITLES, 4)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2,1:0",
                additionalSources = listOf(
                    AdditionalSourceSpec(
                        file = "\${fileName}.srt",
                        tracks = listOf(SourceTrackSpec(language = "en", title = "ExtSubNoCharset")),
                    ),
                ),
            ),
        )

        mux(workDir).exitCode shouldBe 0

        muxedTracks(workDir).ofType("subtitles").size shouldBe 1
    }

    test("two additional sources are both muxed in").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        extractTrack(testMkv, File(workDir, "test[ExtraAudio].mka"), TrackType.AUDIO, 2)
        extractTrack(testMkv, File(workDir, "test[ExtraSub].srt"), TrackType.SUBTITLES, 4)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 1, language = "ja", title = "Japanese", default = true)),
                trackOrder = "0:0,0:1,1:0,2:0",
                additionalSources = listOf(
                    AdditionalSourceSpec(
                        file = "\${fileName}[ExtraAudio].mka",
                        tracks = listOf(SourceTrackSpec(language = "en", title = "ExtraAudio")),
                    ),
                    AdditionalSourceSpec(
                        file = "\${fileName}[ExtraSub].srt",
                        tracks = listOf(SourceTrackSpec(language = "en", title = "ExtraSub")),
                    ),
                ),
            ),
        )

        mux(workDir).exitCode shouldBe 0

        val tracks = muxedTracks(workDir)
        tracks.ofType("audio").size shouldBe 2
        tracks.ofType("subtitles").size shouldBe 1
        tracks.map { it.trackName } shouldContain "ExtraAudio"
        tracks.map { it.trackName } shouldContain "ExtraSub"
    }

    test("a file outside allowedExtensions is reported and skipped").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        File(workDir, "notes.txt").writeText("not a video", StandardCharsets.UTF_8)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "Skipping notes.txt"
        }
        outputNames(workDir) shouldBe listOf("test.mkv")
    }

    test("every input gets an output").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "episode01.mkv")
        stageInput(workDir, "episode02.mkv")
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        outputNames(workDir).size shouldBe 2
    }

    test("a missing destinationDir is created").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                destinationDir = "output_new",
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        File(workDir, "output_new").isDirectory shouldBe true
        findOutput(workDir, "output_new").shouldNotBeNull()
    }

    // A failed file is a normal outcome of a batch, so mux reports it and carries on to the end.
    test("a track id the source lacks is an error, and the batch still finishes")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "episode01.mkv")
        stageInput(workDir, "episode02.mkv")
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 99, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:99",
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "*** Error:"
            run.output shouldContain "*** Done"
        }
    }

    test("a realistic season episode comes out exactly as configured").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "ShowName.S01E01.mkv")
        writeConfig(
            workDir,
            cfg(
                videoLang = "ja",
                audioTracks = listOf(
                    TrackSpec(id = 1, language = "ja", title = "Japanese", default = true),
                    TrackSpec(id = 2, language = "en", title = "English"),
                ),
                subtitleTracks = listOf(
                    TrackSpec(id = 4, language = "en", title = "English", default = true),
                    TrackSpec(id = 6, language = "ja", title = "Japanese"),
                ),
                trackOrder = "0:0,0:1,0:2,0:4,0:6",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        val probed = probe(findOutput(workDir).shouldNotBeNull())
        probed.containerTitle shouldBe "ShowName.S01E01"
        val tracks = probed.allTracks
        tracks.ofType("video").size shouldBe 1
        tracks.ofType("audio").map { Triple(it.language, it.default, it.trackName) } shouldBe listOf(
            Triple("jpn", true, "Japanese"),
            Triple("eng", false, "English"),
        )
        tracks.ofType("subtitles").map { Triple(it.language, it.default, it.trackName) } shouldBe listOf(
            Triple("eng", true, "English"),
            Triple("jpn", false, "Japanese"),
        )
        tracks.none { it.language == "rus" } shouldBe true
    }

    test("--dry-run prints the command and writes nothing").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        val run = mux(workDir, "--dry-run")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "Dry run"
            run.output shouldContain "--track-order"
        }
        // Not even the destination directory.
        File(workDir, "mkv").exists() shouldBe false
    }

    // The process runner passes argv without a shell, so no pattern here is expanded before mux sees it -
    // the cmd.exe situation, which a Linux-only run would otherwise never cover.
    context("masks and --exclude choose which files are muxed").config(enabledOrReasonIf = needsMkvmerge) {
        withData(
            nameFn = { it.label },
            MaskRow("an exact file name", listOf("Show.S01E02.mkv"), listOf("Show.S01E02.mkv")),
            MaskRow(
                "an unexpanded glob, which misses the sample",
                listOf("Show.S01E0[12].mkv"),
                listOf("Show.S01E01.mkv", "Show.S01E02.mkv"),
            ),
            MaskRow(
                "several masks, unioned",
                listOf("Show.S01E01.mkv", "Show.S01E03.mkv"),
                listOf("Show.S01E01.mkv", "Show.S01E03.mkv"),
            ),
            MaskRow(
                "--exclude, from an otherwise full batch",
                listOf("--exclude", "*.sample.mkv"),
                listOf("Show.S01E01.mkv", "Show.S01E02.mkv", "Show.S01E03.mkv"),
            ),
        ) { row ->
            val workDir = tempdir()
            stageBatch(workDir)

            val run = mux(workDir, *row.args.toTypedArray())

            withClue(run.output) { run.exitCode shouldBe 0 }
            outputNames(workDir) shouldBe row.muxed
        }
    }

    test("a mask matching nothing says so and creates nothing").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageBatch(workDir)

        val run = mux(workDir, "Nope.*.mkv")

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "No files match"
            run.output shouldContain "Nope.*.mkv"
        }
        File(workDir, "mkv").exists() shouldBe false
    }

    // Read as a glob, Odd[1].mkv also matches Odd1.mkv, so without the literal rule it could not be selected.
    test("a mask naming an existing file is matched literally, not as a glob")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Odd[1].mkv")
        stageInput(workDir, "Odd1.mkv")
        writeConfig(
            workDir,
            cfg(audioTracks = listOf(TrackSpec(id = 1, language = "ja", title = "Japanese", default = true))),
        )

        val run = mux(workDir, "Odd[1].mkv")

        withClue(run.output) { run.exitCode shouldBe 0 }
        outputNames(workDir) shouldBe listOf("Odd[1].mkv")
    }
})

/** One spelling of "no tracks of this type": [emptied] must come out with none, [kept] with its one. */
private class ListRow(
    val label: String,
    val audio: List<TrackSpec>?,
    val subs: List<TrackSpec>?,
    val trackOrder: String,
    val emptied: String,
    val kept: String,
)

/** Arguments to a mux over [stageBatch], and the outputs they must produce. */
private class MaskRow(val label: String, val args: List<String>, val muxed: List<String>)

private fun mux(workDir: File, vararg args: String): Run = mkvtool("mux", *args, workDir = workDir)

private fun muxedTracks(workDir: File): List<ProbedTrack> = probe(findOutput(workDir).shouldNotBeNull()).allTracks

private fun List<ProbedTrack>.ofType(type: String): List<ProbedTrack> = filter { it.type == type }
