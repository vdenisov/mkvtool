package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The mkvmerge command line, config permutation by config permutation.
 *
 * This is the whole product: everything else `mux` does exists to get one of these built and run. The
 * expectations are the argument lists v1 emits for the configs behind harness cases 01-24 and 96-105 —
 * taken as *lists*, not as a joined string, because a quoting bug in the dry-run printer would hide inside
 * one and every argument boundary here is one mkvmerge parses.
 *
 * No mkvmerge: probing is injected, so a `${'$'}{codec}` case is answered by a faked record.
 */
class MuxCommandLineTest : FunSpec({

    context("track selection") {
        data class Case(val name: String, val config: Config, val expected: List<String>)

        withData(
            nameFn = { it.name },
            Case(
                "one audio and one subtitle name their ids",
                config(
                    audio = listOf(track(2, "en", "English", default = true)),
                    subtitles = listOf(track(4, "en", "English", default = true)),
                ),
                base() + listOf(
                    "--audio-tracks", "2", "--subtitle-tracks", "4",
                    "--language", "0:ja", "--track-name", "0:Show - S01E01",
                    "--language", "2:en", "--track-name", "2:English", "--default-track-flag", "2:yes",
                    "--language", "4:en", "--track-name", "4:English", "--default-track-flag", "4:yes",
                ) + source() + title(),
            ),
            Case(
                // Absent and empty mean the same thing, and it is not "all of them": the config says what
                // to keep, so saying nothing keeps nothing.
                "an omitted audio list becomes --no-audio",
                config(audio = emptyList(), subtitles = listOf(track(4, "en", "English"))),
                base() + listOf(
                    "--no-audio", "--subtitle-tracks", "4",
                    "--language", "0:ja", "--track-name", "0:Show - S01E01",
                    "--language", "4:en", "--track-name", "4:English", "--default-track-flag", "4:no",
                ) + source() + title(),
            ),
            Case(
                "an omitted subtitle list becomes --no-subtitles",
                config(audio = listOf(track(2, "en", "English")), subtitles = emptyList()),
                base() + listOf(
                    "--audio-tracks", "2", "--no-subtitles",
                    "--language", "0:ja", "--track-name", "0:Show - S01E01",
                    "--language", "2:en", "--track-name", "2:English", "--default-track-flag", "2:no",
                ) + source() + title(),
            ),
            Case(
                "several tracks of one type share one option, comma-joined in listed order",
                config(
                    audio = listOf(track(1, "ja", "Japanese", default = true), track(2, "en", "English")),
                    subtitles = listOf(track(4, "en", "English"), track(6, "ja", "Japanese")),
                ),
                base() + listOf(
                    "--audio-tracks", "1,2", "--subtitle-tracks", "4,6",
                    "--language", "0:ja", "--track-name", "0:Show - S01E01",
                    "--language", "1:ja", "--track-name", "1:Japanese", "--default-track-flag", "1:yes",
                    "--language", "2:en", "--track-name", "2:English", "--default-track-flag", "2:no",
                    "--language", "4:en", "--track-name", "4:English", "--default-track-flag", "4:no",
                    "--language", "6:ja", "--track-name", "6:Japanese", "--default-track-flag", "6:no",
                ) + source() + title(),
            ),
            Case(
                "a subtitle charset follows its track name, and only a subtitle takes one",
                config(
                    audio = listOf(track(2, "en", "English", charset = "windows-1251")),
                    subtitles = listOf(track(4, "ru", "Russian", charset = "windows-1251")),
                ),
                base() + listOf(
                    "--audio-tracks", "2", "--subtitle-tracks", "4",
                    "--language", "0:ja", "--track-name", "0:Show - S01E01",
                    // No --sub-charset here, however the config was written: v1 never emitted one for an
                    // audio track.
                    "--language", "2:en", "--track-name", "2:English", "--default-track-flag", "2:no",
                    "--language", "4:ru", "--track-name", "4:Russian",
                    "--sub-charset", "4:windows-1251", "--default-track-flag", "4:no",
                ) + source() + title(),
            ),
        ) { it.config.commandFor() shouldContainExactly it.expected }
    }

    context("titles") {
        test("the video track name and the segment title default to the file name, independently") {
            val command = config(audio = listOf(track(2, "en", "English"))).commandFor()

            command.valueFor("--track-name", "0:") shouldBe "0:Show - S01E01"
            command.valueFor("--title") shouldBe "Show - S01E01"
        }

        test("general.title overrides the segment title and leaves the video track name alone") {
            val command = config(
                audio = listOf(track(2, "en", "English")),
                generalTitle = Template("\${showName} - S\${seasonNum}E\${episodeNum}"),
            ).commandFor(File("Show - S01E01 - Pilot.mkv"))

            command.valueFor("--track-name", "0:") shouldBe "0:Show - S01E01 - Pilot"
            command.valueFor("--title") shouldBe "Show - S01E01"
        }

        test("a video title override is templated, and the segment title is not") {
            val command = config(
                audio = listOf(track(2, "en", "English")),
                videoTitle = Template("\${episodeName} [\${languageName}]"),
                videoLanguage = "ja",
            ).commandFor(File("Show - S01E04 - From Name.mkv"))

            command.valueFor("--track-name", "0:") shouldBe "0:From Name [Japanese]"
            command.valueFor("--title") shouldBe "Show - S01E04 - From Name"
        }

        test("a declared but empty title overrides the default rather than restoring it") {
            // Template(null) is a template that happens to be empty, and clearing the name is what the
            // config asked for. An *absent* key is the case below.
            val command = config(audio = listOf(track(2, "en", "English")), videoTitle = Template(null))
                .commandFor()

            command.valueFor("--track-name", "0:") shouldBe "0:"
        }

        test("a track with no title key gets no --track-name at all") {
            // v1 called toString() on the absent title and threw; omitting leaves the source track's own
            // name in place, which is what "not configured" has to mean.
            val command = config(audio = listOf(TrackConfig(id = 2, language = "en"))).commandFor()

            command.shouldNotContainPair("--track-name", "2:")
            command.valueFor("--language", "0:") shouldBe "0:ja"
        }
    }

    context("languages") {
        test("a track with no language gets no --language, rather than the text 'null'") {
            val command = config(
                audio = listOf(TrackConfig(id = 2, title = Template("English"))),
                videoLanguage = null,
            ).commandFor()

            command shouldNotContain "--language"
            command.valueFor("--track-name", "0:") shouldBe "0:Show - S01E01"
        }

        test("an absent videoTrack is an empty one, not a crash") {
            val command = config(audio = listOf(track(2, "en", "English")), videoTrack = null).commandFor()

            // Still named after the file, and still the only --track-name for id 0.
            command.valueFor("--track-name", "0:") shouldBe "0:Show - S01E01"
        }
    }

    context("additional sources") {
        test("a companion contributes its own group, always addressed as track 0") {
            val command = config(
                audio = listOf(track(1, "ja", "Japanese", default = true)),
                additionalSources = listOf(
                    AdditionalSource(
                        file = "\${fileName}[Studio].mka",
                        // A declared id is ignored: mkvmerge sees a companion as a single-track file, and
                        // v1 hardcoded 0 for that reason.
                        tracks = listOf(TrackConfig(id = 7, language = "ru", title = Template("Studio Dub"))),
                        additionalOptions = listOf("--no-chapters"),
                    ),
                ),
            ).commandFor()

            command.after("(") shouldBe "Show - S01E01.mkv"
            command.dropWhile { it != ")" } shouldContainExactly listOf(
                ")",
                "--language", "0:ru", "--track-name", "0:Studio Dub", "--default-track-flag", "0:no",
                "--no-chapters",
                "(", "Show - S01E01[Studio].mka", ")",
                "--title", "Show - S01E01", "--track-order", ORDER,
            )
        }

        test("the source path is substituted per episode") {
            val command = config(
                audio = listOf(track(1, "ja", "Japanese")),
                additionalSources = listOf(AdditionalSource(file = "\${fileName}.rus.mka")),
            ).commandFor(File("My Show - S01E01 - Pilot.mkv"))

            command.last { it.endsWith(".mka") } shouldBe "My Show - S01E01 - Pilot.rus.mka"
        }
    }

    context("mkvmerge invocation") {
        test("the ui-language option is omitted when neither spelling was accepted") {
            val command = config(audio = listOf(track(2, "en", "English"))).commandFor(uiLanguage = null)

            command.take(5) shouldContainExactly
                listOf(EXE, "--priority", "lower", "--output", "mkv/Show - S01E01.mkv")
        }

        test("mainSource.additionalOptions are passed through verbatim, after the track selection") {
            val command = config(
                audio = listOf(track(2, "en", "English")),
                mainAdditionalOptions = listOf("--no-chapters", "--no-global-tags"),
            ).commandFor()

            command.subList(command.indexOf("--audio-tracks"), command.indexOf("--language")) shouldContainExactly
                listOf("--audio-tracks", "2", "--no-subtitles", "--no-chapters", "--no-global-tags")
        }

        test("the main source is a bare relative name, so mkvmerge must run in the media directory") {
            val command = config(audio = listOf(track(2, "en", "English")))
                .commandFor(File("sub/dir/Show - S01E01.mkv"))

            // The file's own directory never reaches the command line — v1 built this from the base name
            // and the extension, and the child inherits the working directory instead.
            command.after("(") shouldBe "Show - S01E01.mkv"
        }
    }

    context("\${codec}") {
        test("resolves from the probed track the config names, and probes nothing otherwise") {
            var probes = 0
            val cfg = config(
                audio = listOf(track(2, "en", "\${languageName} \${codec}")),
                subtitles = listOf(track(4, "en", "\${languageName} \${codec}")),
            )
            val probe: (File) -> ProbeResult = { file ->
                probes++
                ProbeResult.Probed(
                    file = file,
                    allTracks = listOf(
                        ProbedTrack(2, "audio", "AAC", "A_AAC", "eng", "", false, false),
                        ProbedTrack(4, "subtitles", "SubRip/SRT", "S_TEXT/UTF8", "eng", "", false, false),
                    ),
                    tracks = emptyMap(),
                    chapters = 0,
                )
            }

            val command = cfg.commandFor(usesCodec = true, probe = probe)

            command.valueFor("--track-name", "2:") shouldBe "2:English AAC"
            command.valueFor("--track-name", "4:") shouldBe "4:English SRT"
            // One probe for both tracks: the record is memoized per file.
            probes shouldBe 1
        }

        test("a config that never asks for a codec probes nothing at all") {
            var probes = 0
            config(audio = listOf(track(2, "en", "English")))
                .commandFor(usesCodec = false, probe = { probes++; error("must not probe") })

            probes shouldBe 0
        }

        test("a companion that is not on disk is never probed, and its codec resolves to nothing") {
            val probed = mutableListOf<String>()
            val command = config(
                audio = listOf(track(1, "ja", "Japanese")),
                additionalSources = listOf(
                    AdditionalSource(
                        file = "missing.mka",
                        tracks = listOf(TrackConfig(language = "ru", title = Template("\${codec}"))),
                    ),
                ),
            ).commandFor(
                usesCodec = true,
                probe = { file ->
                    probed += file.name
                    ProbeResult.Probed(file, emptyList(), emptyMap(), 0)
                },
            )

            // A missing companion is the pre-flight's business, not the command builder's: here it simply
            // resolves to nothing, and no subprocess is spent finding that out.
            probed shouldContainExactly listOf("Show - S01E01.mkv")
            command.dropWhile { it != ")" }.valueFor("--track-name", "0:") shouldBe "0:"
        }
    }
})

private const val EXE = "mkvmerge"
private const val ORDER = "0:0,0:1,0:2"

private fun track(
    id: Int,
    language: String,
    title: String,
    default: Boolean = false,
    charset: String? = null,
) = TrackConfig(id = id, language = language, title = Template(title), default = default, charset = charset)

/** The config under test, with everything the command line needs and nothing it does not. */
private fun config(
    audio: List<TrackConfig> = emptyList(),
    subtitles: List<TrackConfig> = emptyList(),
    additionalSources: List<AdditionalSource> = emptyList(),
    generalTitle: Template? = null,
    videoTitle: Template? = null,
    videoLanguage: String? = "ja",
    videoTrack: VideoTrackConfig? = VideoTrackConfig(),
    mainAdditionalOptions: List<String> = emptyList(),
) = Config(
    general = GeneralConfig(destinationDir = "mkv", mkvmergeExe = EXE, title = generalTitle),
    mainSource = MainSourceConfig(
        videoTrack = videoTrack?.copy(language = videoLanguage, title = videoTitle),
        audioTracks = audio,
        subtitleTracks = subtitles,
        additionalOptions = mainAdditionalOptions,
    ),
    additionalSources = additionalSources,
)

private fun Config.commandFor(
    file: File = File("Show - S01E01.mkv"),
    uiLanguage: String? = null,
    usesCodec: Boolean = false,
    probe: (File) -> ProbeResult = { error("no probe expected") },
): List<String> = MuxCommandBuilder(
    File("."),
    MuxOptions(
        config = this,
        mkvmergeExe = EXE,
        uiLanguage = uiLanguage,
        trackOrder = ORDER,
        usesCodec = usesCodec,
    ),
    probe,
).commandFor(file)

/** The head every command shares, for the cases that spell out a whole argument list. */
private fun base() = listOf(EXE, "--priority", "lower", "--output", "mkv/Show - S01E01.mkv")

private fun source() = listOf("(", "Show - S01E01.mkv", ")")

private fun title() = listOf("--title", "Show - S01E01", "--track-order", ORDER)

/**
 * The value of the [option] whose value starts with [prefix].
 *
 * Options repeat — every track contributes its own `--language` and `--track-name` — so the track id the
 * value is prefixed with is what picks one out, which is exactly how mkvmerge reads them too.
 */
private fun List<String>.valueFor(option: String, prefix: String = ""): String {
    val found = windowed(2).firstOrNull { (a, b) -> a == option && b.startsWith(prefix) }
    check(found != null) { "no '$option $prefix...' in $this" }
    return found[1]
}

private fun List<String>.after(token: String): String = this[indexOf(token) + 1]

private fun List<String>.shouldNotContainPair(option: String, valuePrefix: String) {
    val found = windowed(2).any { (a, b) -> a == option && b.startsWith(valuePrefix) }
    check(!found) { "did not expect $option $valuePrefix... in $this" }
}
