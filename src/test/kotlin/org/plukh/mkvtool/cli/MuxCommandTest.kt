package org.plukh.mkvtool.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.AdditionalSource
import org.plukh.mkvtool.core.Config
import org.plukh.mkvtool.core.GeneralConfig
import org.plukh.mkvtool.core.MainSourceConfig
import org.plukh.mkvtool.core.Template
import org.plukh.mkvtool.core.TrackConfig
import org.plukh.mkvtool.core.VideoTrackConfig
import org.plukh.mkvtool.core.muxConfigProblem
import picocli.CommandLine

/**
 * The option set and the gates that run before any file is touched.
 *
 * Everything `mux` refuses, it refuses *up front* — the config, the episode metadata, the templates — and
 * that is the whole difference in policy from `inspect`, which warns about the same things and carries on.
 * `inspect` reports on files and can do it just as well without a config; `mux` cannot mux against a
 * config it did not understand, and a season stamped from one it misread is exactly the confidently-wrong
 * output the tool exists to avoid.
 */
class MuxCommandTest : FunSpec({

    context("options") {
        test("masks are positional and repeatable, and --exclude may be given more than once") {
            val mux = parse("a.mkv", "b*.mkv", "-x", "*.sample.mkv", "--exclude", "c.mkv")

            mux.fileMasks shouldContainExactly listOf("a.mkv", "b*.mkv")
            mux.excludeMasks shouldContainExactly listOf("*.sample.mkv", "c.mkv")
        }

        test("the mode flags bind, in both spellings") {
            val mux = parse("--dry-run", "--no-check", "--strict", "-c", "other.yaml")

            mux.dryRun shouldBe true
            mux.noCheck shouldBe true
            mux.strict shouldBe true
            mux.configPath shouldBe "other.yaml"

            parse("-n").dryRun shouldBe true
        }

        test("defaults are a checked, real run of the current directory") {
            val mux = parse()

            mux.dryRun shouldBe false
            mux.noCheck shouldBe false
            mux.strict shouldBe false
            mux.configPath shouldBe null
            mux.fileMasks.shouldContainExactly(emptyList())
        }

        context("the inspection flags moved out and are not accepted back as aliases") {
            // Two entry points for one report is surface to keep in sync, and picocli already rejects an
            // unknown option clearly. Harness case 109 pins each of these by name.
            withData("--identify", "--check", "--check-verbose") { flag ->
                val thrown = shouldThrow<CommandLine.UnmatchedArgumentException> {
                    mkvtoolCommandLine().parseArgs("mux", flag)
                }
                thrown.message shouldContain "Unknown option: '$flag'"
            }
        }

        test("--help does not list them either, so the move is visible where one would look") {
            val help = mkvtoolCommandLine().subcommands["mux"]!!.getUsageMessage(CommandLine.Help.Ansi.OFF)

            help shouldContain "--dry-run"
            help shouldContain "--no-check"
            help shouldNotContain "--identify"
        }
    }

    context("fields the command line cannot be built without") {
        test("a config with everything it needs has no problem") {
            muxConfigProblem(config()) shouldBe null
        }

        test("no destinationDir is refused, rather than writing into a directory named 'null'") {
            // v1 composed the output path from the absent value and created exactly that directory.
            muxConfigProblem(config(destinationDir = null)) shouldBe "config.yaml has no general.destinationDir"
            muxConfigProblem(config(destinationDir = "")) shouldBe "config.yaml has no general.destinationDir"
        }

        test("a destination that is the current directory is the caller's business, not a problem") {
            muxConfigProblem(config(destinationDir = ".")) shouldBe null
        }

        test("a track with no id is refused, and the offending entry is named by its config path") {
            // Without an id there is no track to select, name or order: every option it would produce
            // addresses a number that is not there.
            muxConfigProblem(
                config(audio = listOf(TrackConfig(id = 1), TrackConfig(language = "en"))),
            ) shouldBe "config.yaml has no id for mainSource.audioTracks[1]"

            muxConfigProblem(
                config(subtitles = listOf(TrackConfig(language = "en"))),
            ) shouldBe "config.yaml has no id for mainSource.subtitleTracks[0]"
        }

        test("an additional source with no file is refused too") {
            muxConfigProblem(
                config(sources = listOf(AdditionalSource(file = "a.mka"), AdditionalSource())),
            ) shouldBe "config.yaml has no file for additionalSources[1]"
        }

        test("a language or a title is genuinely optional, and neither is a problem") {
            // mkvmerge keeps whatever the source track carries, so the builder omits the option instead of
            // inventing a value — which is why these are not in the refusal list.
            muxConfigProblem(config(audio = listOf(TrackConfig(id = 1)))) shouldBe null
            muxConfigProblem(config(videoTrack = null)) shouldBe null
        }
    }
})

private fun parse(vararg args: String): MuxCommand {
    val cmd = mkvtoolCommandLine()
    cmd.parseArgs("mux", *args)
    return cmd.subcommands["mux"]!!.getCommand()
}

private fun config(
    destinationDir: String? = "mkv",
    audio: List<TrackConfig> = listOf(TrackConfig(id = 1, language = "ja", title = Template("Japanese"))),
    subtitles: List<TrackConfig> = emptyList(),
    videoTrack: VideoTrackConfig? = VideoTrackConfig(language = "ja"),
    sources: List<AdditionalSource> = emptyList(),
) = Config(
    general = GeneralConfig(destinationDir = destinationDir),
    mainSource = MainSourceConfig(videoTrack = videoTrack, audioTracks = audio, subtitleTracks = subtitles),
    additionalSources = sources,
)
