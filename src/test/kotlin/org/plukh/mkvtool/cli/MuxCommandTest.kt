package org.plukh.mkvtool.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.plukh.mkvtool.core.AdditionalSource
import org.plukh.mkvtool.core.Config
import org.plukh.mkvtool.core.DeclaredGroup
import org.plukh.mkvtool.core.GeneralConfig
import org.plukh.mkvtool.core.MainSourceConfig
import org.plukh.mkvtool.core.MatcherConfig
import org.plukh.mkvtool.core.Membership
import org.plukh.mkvtool.core.SharedTrack
import org.plukh.mkvtool.core.SourceTrackConfig
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
            // unknown option clearly. The end-to-end tier pins each of these by name.
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
            muxConfigProblem(config(), "config.yaml") shouldBe null
        }

        test("no destinationDir is refused, rather than writing into a directory named 'null'") {
            // v1 composed the output path from the absent value and created exactly that directory.
            muxConfigProblem(config(destinationDir = null), "config.yaml") shouldBe
                "config.yaml has no general.destinationDir"
            muxConfigProblem(config(destinationDir = ""), "config.yaml") shouldBe
                "config.yaml has no general.destinationDir"
        }

        test("a reason names the file the config was actually read from") {
            // Under --config season2.yaml, "config.yaml" would send the reader to a file they did not pass.
            muxConfigProblem(config(destinationDir = null), "season2.yaml") shouldBe
                "season2.yaml has no general.destinationDir"
            val withId = AdditionalSource(file = "a.mka", tracks = listOf(SourceTrackConfig(idDeclared = true)))
            muxConfigProblem(config(sources = listOf(withId)), "season2.yaml")!! shouldStartWith
                "season2.yaml sets an id"
        }

        test("a destination that is the current directory is the caller's business, not a problem") {
            muxConfigProblem(config(destinationDir = "."), "config.yaml") shouldBe null
        }

        test("a track with no id is refused, and the offending entry is named by its config path") {
            // Without an id there is no track to select, name or order: every option it would produce
            // addresses a number that is not there.
            muxConfigProblem(
                config(audio = listOf(TrackConfig(id = 1), TrackConfig(language = "en"))),
                "config.yaml",
            ) shouldBe "config.yaml has no id for mainSource.audioTracks[1]"

            muxConfigProblem(
                config(subtitles = listOf(TrackConfig(language = "en"))),
                "config.yaml",
            ) shouldBe "config.yaml has no id for mainSource.subtitleTracks[0]"
        }

        test("an additional source with no file is refused too") {
            muxConfigProblem(
                config(sources = listOf(AdditionalSource(file = "a.mka"), AdditionalSource())),
                "config.yaml",
            ) shouldBe "config.yaml has no file for additionalSources[1]"
        }

        test("an id on an additional-source track is refused, after a missing file") {
            val withId = AdditionalSource(file = "a.mka", tracks = listOf(SourceTrackConfig(idDeclared = true)))

            muxConfigProblem(config(sources = listOf(withId)), "config.yaml") shouldBe
                "config.yaml sets an id on additionalSources[0].tracks[0], which should be removed: " +
                "an additional-source track is always track 0"
            muxConfigProblem(config(sources = listOf(withId, AdditionalSource())), "config.yaml") shouldBe
                "config.yaml has no file for additionalSources[1]"
        }

        test("a language or a title is genuinely optional, and neither is a problem") {
            // mkvmerge keeps whatever the source track carries, so the builder omits the option instead of
            // inventing a value — which is why these are not in the refusal list.
            muxConfigProblem(config(audio = listOf(TrackConfig(id = 1))), "config.yaml") shouldBe null
            muxConfigProblem(config(videoTrack = null), "config.yaml") shouldBe null
        }

        test("a missing id on an entry built on a shared track names the shared track too") {
            muxConfigProblem(
                config(audio = listOf(TrackConfig(language = "ja", ref = "jpn"))).copy(
                    sharedTracks = listOf(SharedTrack("jpn")),
                ),
                "config.yaml",
            ) shouldBe "config.yaml has no id for mainSource.audioTracks[0] (use: jpn)"
        }

        test("a group or shared-track problem is refused, before the per-track checks") {
            muxConfigProblem(config(audio = listOf(TrackConfig(ref = "jpn"))), "config.yaml") shouldBe
                "config.yaml uses an unknown shared track 'jpn' at mainSource.audioTracks[0]"
        }

        test("a grouped config is muxed with") {
            val grouped = Config(
                general = GeneralConfig(destinationDir = "mkv"),
                declaredGroups = listOf(
                    DeclaredGroup(name = "raw", membership = Membership(episodes = listOf(1..6))),
                    DeclaredGroup(name = "dubbed", membership = Membership(episodes = listOf(7..12))),
                ),
            )

            muxConfigProblem(grouped, "config.yaml") shouldBe null
        }

        test("a group's own problem is refused under the group's config path") {
            val grouped = Config(
                general = GeneralConfig(destinationDir = "mkv"),
                declaredGroups = listOf(
                    DeclaredGroup(
                        name = "raw",
                        membership = Membership(episodes = listOf(1..6)),
                        mainSource = MainSourceConfig(audioTracks = listOf(TrackConfig())),
                    ),
                ),
            )

            muxConfigProblem(grouped, "config.yaml") shouldBe
                "config.yaml has no id for groups[0].mainSource.audioTracks[0]"
        }

        test("an inherited problem is refused under the top-level path it is written at") {
            val grouped = Config(
                general = GeneralConfig(destinationDir = "mkv"),
                mainSource = MainSourceConfig(audioTracks = listOf(TrackConfig())),
                declaredGroups = listOf(DeclaredGroup(name = "raw", membership = Membership(episodes = listOf(1..6)))),
            )

            muxConfigProblem(grouped, "config.yaml") shouldBe "config.yaml has no id for mainSource.audioTracks[0]"
        }

        test("binding keys and an outputName are muxed with, not refused") {
            val named = config().copy(general = GeneralConfig(destinationDir = "mkv", outputName = Template("x")))
            val bound = config(sources = listOf(AdditionalSource(files = listOf("Rus sound/*.mka"))))

            muxConfigProblem(named, "config.yaml") shouldBe null
            // A source with files: names no file on purpose, so it is not reported as having none.
            muxConfigProblem(bound, "config.yaml") shouldBe null
        }

        test("a binding problem is a refusal") {
            val pinned = config().copy(
                general = GeneralConfig(destinationDir = "mkv", matching = MatcherConfig(matcher = "fuzzy")),
            )

            muxConfigProblem(pinned, "config.yaml") shouldBe
                "config.yaml has an unknown matcher 'fuzzy' at general.matcher; the only pin is 'sequence'"
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
