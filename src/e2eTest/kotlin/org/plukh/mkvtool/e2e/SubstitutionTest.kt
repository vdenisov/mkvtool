package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.ProbedTrack
import org.plukh.mkvtool.e2e.support.AdditionalSourceSpec
import org.plukh.mkvtool.e2e.support.Run
import org.plukh.mkvtool.e2e.support.SourceTrackSpec
import org.plukh.mkvtool.e2e.support.TrackSpec
import org.plukh.mkvtool.e2e.support.TrackType
import org.plukh.mkvtool.e2e.support.cfg
import org.plukh.mkvtool.e2e.support.extractTrack
import org.plukh.mkvtool.e2e.support.findOutput
import org.plukh.mkvtool.e2e.support.fullCopy
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvmerge
import org.plukh.mkvtool.e2e.support.probe
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.writeConfig
import org.plukh.mkvtool.e2e.support.writeEpisodes
import org.plukh.mkvtool.e2e.support.writeEpisodesYaml
import java.io.File

/**
 * `${'$'}{...}` templates in `config.yaml`, end to end: what `mux` stamps into the output, what stage one refuses
 * before anything is probed, and what stage two drops per file.
 */
class SubstitutionTest : FunSpec({

    test("the segment title is templated independently of the video track name")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "My Show - S01E03 - Ignored.mkv")
        writeEpisodesYaml(workDir, show = "My Show", seasonName = "Season One", episodes = mapOf(3 to "Real: Title?"))
        writeConfig(
            workDir,
            cfg(
                generalTitle = "\${showName} - S\${seasonNum}E\${episodeNum} - \${episodeName}",
                audioTracks = english,
                trackOrder = "0:0,0:2",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        val probed = probe(findOutput(workDir).shouldNotBeNull())
        // The raw episode name: the colon and question mark only ever leave a file name, never a title.
        probed.containerTitle shouldBe "My Show - S01E03 - Real: Title?"
        probed.allTracks.single { it.type == "video" }.trackName shouldBe "My Show - S01E03 - Ignored"
    }

    test("the video title is templated, and the segment title keeps the file name")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "My Show - S01E04 - From Name.mkv")
        writeConfig(
            workDir,
            cfg(
                videoTitle = "\${episodeName} [\${languageName}]",
                videoLang = "ja",
                audioTracks = english,
                trackOrder = "0:0,0:2",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        val probed = probe(findOutput(workDir).shouldNotBeNull())
        // No episodes.yaml: the episode name comes from the canonical file name.
        probed.allTracks.single { it.type == "video" }.trackName shouldBe "From Name [Japanese]"
        probed.containerTitle shouldBe "My Show - S01E04 - From Name"
    }

    test("language names resolve in English and natively, from two- and three-letter codes")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(
                    TrackSpec(id = 2, language = "en", title = "\${languageName}", default = true),
                    TrackSpec(id = 3, language = "ru", title = "\${languageNative}"),
                ),
                subtitleTracks = listOf(TrackSpec(id = 6, language = "jpn", title = "\${languageName}")),
                trackOrder = "0:0,0:2,0:3,0:6",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        val tracks = muxedTracks(workDir)
        // The JDK spells Russian's own name in lower case; the tool capitalises it by Russian's rules.
        tracks.filter { it.type == "audio" }.map { it.trackName } shouldBe listOf("English", "Русский")
        tracks.filter { it.type == "subtitles" }.map { it.trackName } shouldBe listOf("Japanese")
    }

    // --no-check, so no pre-flight has filled the probe cache: this is the probe-on-demand path.
    test("\${codec} comes from the probe, with the check off").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(
                    TrackSpec(id = 2, language = "en", title = "\${languageName} \${codec}", default = true),
                ),
                subtitleTracks = listOf(
                    TrackSpec(id = 4, language = "en", title = "\${languageName} \${codec}", default = true),
                ),
                trackOrder = "0:0,0:2,0:4",
            ),
        )

        mux(workDir, "--no-check").exitCode shouldBe 0

        val tracks = muxedTracks(workDir)
        tracks.single { it.type == "audio" }.trackName shouldBe "English AAC"
        tracks.single { it.type == "subtitles" }.trackName shouldBe "English SRT"
    }

    // A typo would otherwise be stamped into the track names of a whole season, one file at a time.
    test("an unknown variable is fatal before anything is muxed").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e01.mkv")
        stageInput(workDir, "Show.s01e02.mkv")
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "\${languagName}", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "\${languagName}"
            run.output shouldContain "mainSource.audioTracks[0].title"
            // The valid names are listed, so the fix is on screen.
            run.output shouldContain "languageName"
        }
        File(workDir, "mkv").exists() shouldBe false
    }

    test("a track variable in a file-scope field is fatal").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(generalTitle = "\${languageName}", audioTracks = english, trackOrder = "0:0,0:2"),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "general.title"
        }
        File(workDir, "mkv").exists() shouldBe false
    }

    test("a language code with no display name is fatal").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir)
        writeConfig(
            workDir,
            cfg(
                audioTracks = listOf(TrackSpec(id = 2, language = "xx", title = "\${languageName}", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 2
            run.output shouldContain "no language name for 'xx'"
        }
        File(workDir, "mkv").exists() shouldBe false
    }

    // Missing data is per file, unlike a typo: the rest of the batch still muxes, unless --strict says not to.
    test("a variable with no data for one episode drops that file, and --strict aborts instead")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "My Show - S01E01 - One.mkv")
        stageInput(workDir, "NoEpisodeNumberHere.mkv")
        writeEpisodesYaml(workDir, show = "My Show", episodes = mapOf(1 to "One"))
        writeConfig(
            workDir,
            cfg(
                generalTitle = "\${showName} - S\${seasonNum}E\${episodeNum} - \${episodeName}",
                audioTracks = english,
                trackOrder = "0:0,0:2",
            ),
        )

        val run = mux(workDir)

        withClue(run.output) {
            run.exitCode shouldBe 0
            run.output shouldContain "NoEpisodeNumberHere.mkv"
            run.output shouldContain "\${episodeNum}"
        }
        File(workDir, "mkv").list().orEmpty().sorted() shouldBe listOf("My Show - S01E01 - One.mkv")

        File(workDir, "mkv").deleteRecursively()
        val strict = mux(workDir, "--strict")

        withClue(strict.output) {
            strict.exitCode shouldBe 2
            strict.output shouldContain "unresolved substitution variables"
        }
        File(workDir, "mkv").exists() shouldBe false
    }

    test("episodes.txt supplies \${episodeName} by line number").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "Show.s01e02.mkv")
        // Hand-written and raw: the colon is what a file name cannot hold and a title can.
        writeEpisodes(workDir, listOf("First One", "Second: With Colon?"))
        writeConfig(
            workDir,
            cfg(generalTitle = "\${episodeName}", audioTracks = english, trackOrder = "0:0,0:2"),
        )

        mux(workDir).exitCode shouldBe 0

        probe(findOutput(workDir).shouldNotBeNull()).containerTitle shouldBe "Second: With Colon?"
    }

    test("a companion's path and title are templated").config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        val input = stageInput(workDir, "My Show - S01E01 - Pilot.mkv")
        extractTrack(input, File(workDir, "My Show - S01E01 - Pilot.rus.mka"), TrackType.AUDIO, 3)
        writeConfig(
            workDir,
            cfg(
                audioTracks = english,
                additionalSources = listOf(
                    AdditionalSourceSpec(
                        file = "\${fileName}.rus.mka",
                        tracks = listOf(SourceTrackSpec(language = "ru", title = "\${languageNative} \${codec}")),
                    ),
                ),
                trackOrder = "0:0,0:2,1:0",
            ),
        )

        mux(workDir).exitCode shouldBe 0

        // From the companion's own language and codec, not the main file's.
        muxedTracks(workDir).filter { it.type == "audio" }.map { it.trackName } shouldBe
            listOf("English", "Русский AAC")
    }

    // The check runs over the whole batch and cannot resolve a per-file variable; the raw template is what
    // identifies the config entry a finding refers to.
    test("--dry-run resolves templates, and the check report keeps them raw")
        .config(enabledOrReasonIf = needsMkvmerge) {
        val workDir = tempdir()
        stageInput(workDir, "My Show - S01E01 - Pilot.mkv")
        writeConfig(
            workDir,
            cfg(
                generalTitle = "\${showName}: \${episodeName}",
                audioTracks = listOf(TrackSpec(id = 2, language = "en", title = "\${languageName}", default = true)),
                trackOrder = "0:0,0:2",
            ),
        )

        val dry = mux(workDir, "--dry-run")

        withClue(dry.output) {
            dry.exitCode shouldBe 0
            dry.output shouldContain "My Show: Pilot"
            dry.output shouldNotContain "\${showName}"
        }
        File(workDir, "mkv").exists() shouldBe false

        // A name split at id 2, the selected audio track, makes the finding blocking and prints its config title.
        fullCopy(File(workDir, "My Show - S01E02 - Second.mkv"), names = mapOf(2 to "Different"))
        val check = mkvtool("inspect", "--check", workDir = workDir)

        withClue(check.output) {
            check.exitCode shouldBe 0
            check.output shouldContain "\${languageName}"
        }
    }
})

private val english = listOf(TrackSpec(id = 2, language = "en", title = "English", default = true))

private fun mux(workDir: File, vararg args: String): Run = mkvtool("mux", *args, workDir = workDir)

private fun muxedTracks(workDir: File): List<ProbedTrack> = probe(findOutput(workDir).shouldNotBeNull()).allTracks
