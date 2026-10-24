package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.plukh.mkvtool.core.BindingProblem
import org.plukh.mkvtool.core.BindingResult
import org.plukh.mkvtool.core.CompanionDrops
import org.plukh.mkvtool.core.DuplicateEpisode
import org.plukh.mkvtool.core.FileMux
import org.plukh.mkvtool.core.MissingCompanion
import org.plukh.mkvtool.core.MuxAbort
import org.plukh.mkvtool.core.MuxOutcome
import org.plukh.mkvtool.core.NameTie
import org.plukh.mkvtool.core.SeasonConflict
import org.plukh.mkvtool.core.SeasonUnknown
import org.plukh.mkvtool.core.SequenceCount
import org.plukh.mkvtool.core.SharedFile
import org.plukh.mkvtool.core.SharedMain
import org.plukh.mkvtool.core.SkippedMainFile
import org.plukh.mkvtool.core.SubstitutionDrops
import org.plukh.mkvtool.core.TrackOrder
import org.plukh.mkvtool.core.TreeEntry
import org.plukh.mkvtool.core.UnpairedFile
import org.plukh.mkvtool.core.UnresolvedVariable
import org.plukh.mkvtool.core.extensionOf
import java.io.File

/**
 * What a mux says beyond mkvmerge's own output — which is not much, and deliberately so: the child owns
 * the console while it runs, and these renderers fill in only what it cannot say.
 *
 * Routing is half of what is pinned here. The warnings are stderr, the previews and the skips are stdout,
 * and a failure is both — a blank line on stdout separating it from mkvmerge's last words, and the error
 * itself on stderr.
 */
class MuxRendererTest : FunSpec({

    context("track order") {
        test("a derived order is announced, followed by a blank line") {
            val (out, err) = renderResult(TrackOrderDerivedRenderer, TrackOrder.Derived("0:0,0:1,0:4")).lf()

            out shouldBe "*** trackOrder not configured; using derived order: 0:0,0:1,0:4\n\n"
            err shouldBe ""
        }

        test("a configured order with nothing wrong says nothing at all") {
            val (out, err) = renderResult(TrackOrderConfiguredRenderer, configured()).lf()

            out shouldBe ""
            err shouldBe ""
        }

        test("a malformed entry is quoted, with the shape it should have had") {
            val (_, err) = renderResult(TrackOrderConfiguredRenderer, configured(malformed = listOf("bogus")))

            err shouldContain "*** Warning: trackOrder contains malformed entries: bogus"
            err shouldContain """Expected comma-separated sourceIndex:trackId pairs, e.g. "0:0,0:1,1:0"."""
        }

        test("an unknown id says why it matters: mkvmerge would ignore it in silence") {
            val (_, err) = renderResult(TrackOrderConfiguredRenderer, configured(unknown = listOf("0:999")))

            err shouldContain "*** Warning: trackOrder references track IDs not configured: 0:999"
            err shouldContain "mkvmerge silently ignores unknown IDs, so these have no effect."
            err shouldContain "Check trackOrder against mainSource.audioTracks / subtitleTracks / additionalSources."
        }

        test("an omitted id says the track is still muxed, just not placed") {
            val (_, err) = renderResult(TrackOrderConfiguredRenderer, configured(missing = listOf("0:2")))

            err shouldContain "*** Warning: trackOrder omits configured track IDs: 0:2"
            err shouldContain "These tracks are still muxed, but their position in the output is left to mkvmerge."
        }

        test("all three findings are reported together, each with its own continuation") {
            val (_, err) = renderResult(
                TrackOrderConfiguredRenderer,
                configured(malformed = listOf("bogus"), unknown = listOf("0:999"), missing = listOf("0:2")),
            )

            err.lines().filter { it.startsWith("*** Warning:") }.size shouldBe 3
        }
    }

    context("the pre-flight drops") {
        test("stage two names the count, then each variable and the episodes it failed for") {
            val (out, err) = renderResult(
                SubstitutionDropsRenderer,
                SubstitutionDrops(
                    variables = listOf(
                        UnresolvedVariable("episodeNum", listOf("Odd.mkv")),
                        UnresolvedVariable("episodeName", listOf("Odd.mkv", "Other.mkv")),
                    ),
                    fileNames = listOf("Odd.mkv", "Other.mkv"),
                    episodeSource = "episodes.yaml",
                ),
            ).lf()

            out shouldBe "*** 2 files will be skipped: substitution variables have no value\n" +
                "      \${episodeNum}  (unresolved for 1 file)\n" +
                "        Odd.mkv\n" +
                "      \${episodeName}  (unresolved for 2 files)\n" +
                "        Odd.mkv\n" +
                "        Other.mkv\n" +
                "\n"
            err shouldBe ""
        }

        test("with no metadata at all it says so first, since that explains every line under it") {
            val (out, _) = renderResult(
                SubstitutionDropsRenderer,
                SubstitutionDrops(
                    variables = listOf(UnresolvedVariable("episodeName", listOf("a.mkv"))),
                    fileNames = listOf("a.mkv"),
                    episodeSource = null,
                ),
            ).lf()

            out.lines()[1] shouldBe "      no episodes.yaml or episodes.txt in this directory"
        }

        test("a long list of episodes truncates, as the check report's evidence lists do") {
            val many = (1..12).map { "S01E%02d.mkv".format(it) }
            val (out, _) = renderResult(
                SubstitutionDropsRenderer,
                SubstitutionDrops(listOf(UnresolvedVariable("episodeName", many)), many, "episodes.txt"),
            ).lf()

            out shouldContain "        ... and 4 more"
        }

        test("the companion drop names the pattern unresolved, not what it expanded to") {
            // The pattern is the line in the config to go and look at; a resolved path is one episode's
            // story, and there are usually several.
            val (out, err) = renderResult(
                CompanionDropsRenderer,
                CompanionDrops(
                    sources = listOf(MissingCompanion("\${fileName}[Studio].mka", listOf("S01E02.mkv"))),
                    fileNames = listOf("S01E02.mkv"),
                ),
            ).lf()

            out shouldBe "*** 1 file will be skipped: companion files are missing\n" +
                "      \${fileName}[Studio].mka  (missing for 1 file)\n" +
                "        S01E02.mkv\n" +
                "\n"
            err shouldBe ""
        }
    }

    context("--strict aborts") {
        test("stage two's abort counts the files it could not resolve") {
            val (out, err) = renderResult(
                UnresolvedVariablesAbortRenderer,
                MuxAbort.UnresolvedVariables(3),
            ).lf()

            err shouldBe "*** Strict mode: aborting (3 files with unresolved substitution variables).\n"
            out shouldBe ""
        }

        test("the check's abort counts the discrepancies and says nothing was written") {
            val (out, err) = renderResult(
                BlockingDiscrepanciesAbortRenderer,
                MuxAbort.BlockingDiscrepancies(2),
            ).lf()

            err shouldBe "*** Strict mode: aborting (2 discrepancies affecting selected tracks).\n" +
                "*** Nothing was muxed. Fix the config or the inputs, or drop --strict to continue.\n"
            out shouldBe ""
        }

        test("one discrepancy is singular, and it is the irregular plural that gets it wrong") {
            val (_, err) = renderResult(
                BlockingDiscrepanciesAbortRenderer,
                MuxAbort.BlockingDiscrepancies(1),
            ).lf()

            err shouldContain "(1 discrepancy affecting selected tracks)"
        }
    }

    context("binding") {
        test("a clean binding says nothing") {
            renderResult(BindingResultRenderer, binding()).lf() shouldBe ("" to "")
        }

        test("a main no rule read is a warning naming it, and the batch goes on") {
            val (out, err) = renderResult(BindingResultRenderer, binding(skipped = listOf(File("NCOP.mkv")))).lf()

            out shouldBe ""
            err shouldBe "*** Warning: 1 main file has no episode and will be skipped:\n      NCOP.mkv\n"
        }

        context("each problem is a line under the error, naming the files and the source") {
            withData(
                nameFn = { it.second },
                SeasonUnknown to "no season: no file name and no episode metadata names one",
                SeasonConflict(mapOf("01" to listOf("a.mkv"), "02" to listOf("b.mkv")), "01") to
                    "more than one season; the episode metadata says 01:\n" +
                    "        season 01:\n          a.mkv\n        season 02:\n          b.mkv",
                DuplicateEpisode(3, listOf(File("a.mkv"), File("b.mkv"))) to
                    "episode 3 is claimed by 2 main files: a.mkv, b.mkv",
                SequenceCount("general", 11, 12) to
                    "general.matcher is sequence over 11 main files, but the episode metadata lists 12",
                SequenceCount("general", 11, null) to
                    "general.matcher is sequence over 11 main files, but there is no episode metadata to count against",
                SequenceCount("general", 1, 12) to
                    "general.matcher is sequence over 1 main file, but the episode metadata lists 12",
                SequenceCount("additionalSources[0]", 5, 1) to
                    "additionalSources[0].matcher is sequence over 5 files, but 1 main file has an episode",
                SequenceCount("additionalSources[0]", 1, 2) to
                    "additionalSources[0].matcher is sequence over 1 file, but 2 main files have an episode",
                UnpairedFile("additionalSources[0]", entry("Subs/x.srt")) to
                    "additionalSources[0]: Subs/x.srt pairs with no main file",
                SkippedMainFile("additionalSources[0]", entry("NCOP.ass"), File("NCOP.mkv")) to
                    "additionalSources[0]: NCOP.ass is named after NCOP.mkv, which has no episode",
                NameTie("additionalSources[0]", entry("X.srt"), listOf(File("X.mkv"), File("X.mp4"))) to
                    "additionalSources[0]: X.srt is named after more than one main file: X.mkv, X.mp4",
                SharedMain("additionalSources[0]", File("A.mkv"), listOf(entry("a.srt"), entry("b.srt"))) to
                    "additionalSources[0]: more than one file pairs with A.mkv: a.srt, b.srt",
                SharedFile(entry("Subs/x.srt"), listOf("additionalSources[0]", "additionalSources[1]")) to
                    "Subs/x.srt is matched by more than one source: additionalSources[0], additionalSources[1]",
            ) { (problem, lines) ->
                val (out, err) = renderResult(BindingResultRenderer, binding(problems = listOf(problem))).lf()

                out shouldBe ""
                err shouldBe "*** Error: the files cannot be bound to episodes (1 problem):\n      $lines\n"
            }
        }

        test("the refusal says nothing was muxed, on stderr") {
            renderResult(UnboundAbortRenderer, MuxAbort.Unbound(2)).lf() shouldBe
                ("" to "*** Nothing was muxed. Fix the file names or the config.\n")
        }

        test("an output collision names the path and the files resolving to it") {
            val (out, err) = renderResult(
                OutputCollisionsAbortRenderer,
                MuxAbort.OutputCollisions(mapOf("mkv/Show.mkv" to listOf("a.mkv", "a.mp4"))),
            ).lf()

            out shouldBe ""
            err shouldBe "*** Error: 2 files would be written to mkv/Show.mkv:\n" +
                "      a.mkv\n      a.mp4\n" +
                "*** Nothing was muxed. Give each episode its own output name.\n"
        }
    }

    context("one file") {
        test("a skipped file is one plain line, and nothing more") {
            val (out, err) = renderResult(FileMuxRenderer, FileMux("notes.txt", null, null, MuxOutcome.Skipped)).lf()

            out shouldBe "*** Skipping notes.txt\n"
            err shouldBe ""
        }

        test("a muxed file prints nothing: mkvmerge already had the console") {
            val (out, err) = renderResult(FileMuxRenderer, fileMux(MuxOutcome.Muxed)).lf()

            out shouldBe ""
            err shouldBe ""
        }

        test("a failure is a blank line on stdout and the error on stderr") {
            val (out, err) = renderResult(FileMuxRenderer, fileMux(MuxOutcome.Failed(2))).lf()

            out shouldBe "\n"
            err shouldBe "*** Error: mkvmerge exited with code 2\n"
        }

        test("a dry run prints the command, quoting only the arguments that need it") {
            // The quoting is what makes the line paste-able, and it is per argument rather than around the
            // whole thing: a reader checks argument boundaries here as often as values.
            val (out, err) = renderResult(
                FileMuxRenderer,
                FileMux(
                    "a.mkv",
                    "mkv/a.mkv",
                    listOf("mkvmerge", "--output", "mkv/Show - S01E01.mkv", "--audio-tracks", "2"),
                    MuxOutcome.Previewed,
                ),
            ).lf()

            out shouldBe "*** Dry run, would execute:\n" +
                "mkvmerge --output \"mkv/Show - S01E01.mkv\" --audio-tracks 2\n" +
                "\n"
            err shouldBe ""
        }
    }
})

/**
 * Captured output with the platform separator folded to a bare newline, so these specs can pin blank
 * lines and "nothing more than this" without also asserting which OS ran them.
 */
private fun Pair<String, String>.lf(): Pair<String, String> =
    first.replace("\r\n", "\n") to second.replace("\r\n", "\n")

private fun configured(
    malformed: List<String> = emptyList(),
    unknown: List<String> = emptyList(),
    missing: List<String> = emptyList(),
) = TrackOrder.Configured("0:0,0:1", malformed, unknown, missing)

private fun binding(skipped: List<File> = emptyList(), problems: List<BindingProblem> = emptyList()) =
    BindingResult("01", emptyList(), skipped, emptyList(), problems)

private fun entry(relPath: String): TreeEntry {
    val name = relPath.substringAfterLast('/')
    val dir = relPath.substringBeforeLast('/', "")
    val base = name.substringBeforeLast('.')
    return TreeEntry(File(relPath), relPath, dir, dir.ifEmpty { null }, base, extensionOf(name))
}

private fun fileMux(outcome: MuxOutcome) =
    FileMux("a.mkv", "mkv/a.mkv", listOf("mkvmerge", "--output", "mkv/a.mkv"), outcome)
