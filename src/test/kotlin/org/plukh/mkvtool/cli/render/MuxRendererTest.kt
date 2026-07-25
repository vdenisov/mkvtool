package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.plukh.mkvtool.core.FileMux
import org.plukh.mkvtool.core.MuxOutcome
import org.plukh.mkvtool.core.TrackOrder

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

private fun fileMux(outcome: MuxOutcome) =
    FileMux("a.mkv", "mkv/a.mkv", listOf("mkvmerge", "--output", "mkv/a.mkv"), outcome)
