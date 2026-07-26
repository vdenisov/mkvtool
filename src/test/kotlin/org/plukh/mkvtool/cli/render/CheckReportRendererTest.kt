package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.ProbeResult
import org.plukh.mkvtool.core.TrackSelection
import java.io.File

/**
 * The check report's text form. These pin the substrings the Groovy harness asserts on, plus the layout
 * rules that make the table readable.
 *
 * The whole report was verified byte-identical to v1 over eleven scenarios before these were written;
 * what is kept here is the subset that says why each piece of the rendering exists.
 */
class CheckReportRendererTest : FunSpec({

    val withConfig = TrackSelection(
        hasConfig = true,
        videoIds = setOf(0),
        audioIds = setOf(1),
        subtitleIds = setOf(4),
        titleById = mapOf(1 to "Japanese"),
    )

    context("header and summary") {
        test("names the caller's label and counts the readable files") {
            // The same report is a pre-flight when mux runs it before muxing and the whole point of the
            // run when inspect does.
            render(listOf(probed("e01.mkv", t(0, "video"))), label = "Consistency check") shouldContain
                "*** Consistency check: 1 file"
            render(listOf(probed("e01.mkv", t(0, "video"))), label = "Pre-flight check") shouldContain
                "*** Pre-flight check: 1 file"
        }

        test("a clean batch says so, and says whether externals were part of it") {
            val plain = listOf(probed("e01.mkv", t(0, "video")), probed("e02.mkv", t(0, "video")))

            render(plain) shouldContain "*** Track structure is consistent across 2 files."

            val externals = mapOf(
                "e01.mkv" to mapOf("A/audio/mka" to ex("A")),
                "e02.mkv" to mapOf("A/audio/mka" to ex("A")),
            )
            render(plain, externals = externals) shouldContain
                "*** Track structure and external files are consistent across 2 files."
        }

        test("unreadable files are excluded from the count and listed with their reason") {
            val output = render(
                listOf(
                    probed("e01.mkv", t(0, "video")),
                    ProbeResult.Failed(File("broken.mkv"), "not recognised as a media file"),
                )
            )

            output shouldContain "*** Consistency check: 1 file (1 could not be identified by mkvmerge and are excluded)"
            output shouldContain "      broken.mkv (not recognised as a media file)"
        }

        test("a batch with nothing readable stops after the header") {
            val output = render(listOf(ProbeResult.Failed(File("x.mkv"), "mkvmerge exit 2")))

            output shouldContain "x.mkv (mkvmerge exit 2)"
            output shouldNotContain "ID   TYPE"
            output shouldNotContain "consistent across"
        }

        test("without a config the differences are counted and the reader is pointed at how to classify") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio", language = "jpn")),
                probed("e02.mkv", t(0, "video"), t(1, "audio", language = "rus")),
            )

            val output = render(infos, selection = TrackSelection.NONE)

            output shouldContain "*** 1 difference across the batch (see the tables above)."
            output shouldContain "***   Add a config.yaml, or --config <path>, to classify which affect selected tracks."
            // The per-item labels all assume selected tracks, so they are only printed with a config.
            output shouldNotContain "informational"
        }
    }

    context("findings") {
        test("a blocking discrepancy names the track, its configured title and what differs") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio", language = "jpn"), t(2, "audio")),
                probed("e02.mkv", t(0, "video"), t(1, "audio", language = "rus"), t(2, "audio")),
            )

            val output = render(infos, selection = withConfig)

            output shouldContain "*** 1 discrepancy affects a track that config.yaml selects:"
            output shouldContain "      track 1 (audio, config title \"Japanese\") - language differs across 2 groups"
        }

        test("several differing fields are listed together and the verb agrees") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio", codec = "AC-3", language = "eng"), t(2, "audio")),
                probed("e02.mkv", t(0, "video"), t(1, "audio", codec = "DTS", language = "rus"), t(2, "audio")),
            )

            render(infos, selection = withConfig) shouldContain "codec, language differ across 2 groups"
        }

        test("a layout outlier at a selected id says which track it lands on") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio")),
                probed("e02.mkv", t(0, "video"), t(1, "audio")),
                probed("e03.mkv", t(0, "video"), t(1, "subtitles")),
            )

            render(infos, selection = withConfig) shouldContain
                "1 file uses a different track layout, at selected track 1"
        }

        test("a group differing only in its externals says it needs its own pass") {
            // Never blocking — nothing selects an external file by id — but it is a separate muxing pass,
            // which is the whole point of saying it.
            val infos = listOf(probed("e01.mkv", t(0, "video")), probed("e02.mkv", t(0, "video")))
            val externals = mapOf("e01.mkv" to mapOf("A/audio/mka" to ex("A")))

            val output = render(infos, externals = externals, selection = withConfig)

            output shouldContain "1 file uses a different set of external files, so they need their own pass"
            output shouldContain "1 informational (does not affect what gets muxed):"
        }

        test("an external value split is attributed to its variant") {
            val infos = listOf(probed("e01.mkv", t(0, "video")), probed("e02.mkv", t(0, "video")))
            val externals = mapOf(
                "e01.mkv" to mapOf("A/audio/mka" to ex("A", language = "rus")),
                "e02.mkv" to mapOf("A/audio/mka" to ex("A", language = "und")),
            )

            render(infos, externals = externals, selection = withConfig) shouldContain
                "external A [GroupA] (audio) - language differs across 2 groups"
        }

        test("ambiguous tracks are described once and say whether the config selects one") {
            val infos = listOf(
                probed(
                    "e01.mkv",
                    t(0, "video"),
                    t(1, "audio", codec = "AC-3", language = "eng"),
                    t(2, "audio", codec = "AC-3", language = "eng"),
                )
            )

            val output = render(infos, selection = withConfig)

            output shouldContain "*** Ambiguous track IDs"
            output shouldContain "    Tracks 1 and 2 are both audio / eng / AC-3 with no name, in 1 file."
            output shouldContain "    ID-based selection cannot distinguish them; check which one config.yaml means."
            output shouldContain "tracks 1, 2 are ambiguous and config.yaml selects track 1"
        }

        test("a chapter split is reported with the minority named") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), chapters = 4),
                probed("e02.mkv", t(0, "video"), chapters = 0),
                probed("e03.mkv", t(0, "video"), chapters = 0),
            )

            val output = render(infos, selection = withConfig)

            output shouldContain "*** Chapters: present in 1 file, absent in 2"
            output shouldContain "<- e01.mkv"
            output shouldContain "chapters are present in some files and not others"
        }
    }

    context("the table") {
        test("has one row per track, whether or not it varies") {
            // It doubles as the batch's authoritative track map, which is what you read to check
            // config.yaml's numeric ids against reality.
            val output = render(
                listOf(probed("e01.mkv", t(0, "video"), t(1, "audio", language = "jpn"), t(2, "subtitles")))
            )

            output shouldContain "    ID   TYPE   CODEC                LANG  DEF  FOR  NAME"
            output shouldContain "    0    video"
            output shouldContain "    1    audio  AAC                  jpn"
            output shouldContain "    2    subs"
        }

        test("an unnamed track shows '-' rather than an empty cell") {
            // "-" is the one glyph for "nothing here"; an empty cell could not highlight visibly when a
            // name splits between empty and set.
            render(listOf(probed("e01.mkv", t(1, "audio", name = "")))) shouldContain
                "    1    audio  AAC                  eng   no   no   -"
        }

        test("a video row shows the shared title, or says it varies per file") {
            render(
                listOf(probed("e01.mkv", t(0, "video", name = "Same")), probed("e02.mkv", t(0, "video", name = "Same")))
            ) shouldContain "    0    video  AAC                  eng   no   no   Same"

            render(
                listOf(probed("e01.mkv", t(0, "video", name = "One")), probed("e02.mkv", t(0, "video", name = "Two")))
            ) shouldContain "(per file)"
        }

        test("an over-long name is truncated with ASCII dots, not an ellipsis") {
            // This output lands on Windows consoles running a legacy codepage.
            val long = "N".repeat(80)
            val output = render(listOf(probed("e01.mkv", t(1, "audio", name = long))))

            output shouldContain "N".repeat(57) + "..."
            output shouldNotContain "…"
        }

        test("a minority value names its files above its own row, the majority stays unnamed") {
            val infos = (1..4).map { i ->
                probed("e0$i.mkv", t(0, "video"), t(1, "audio", language = if (i == 4) "rus" else "jpn"))
            }

            val output = render(infos, selection = withConfig)
            val lines = output.lines()
            val markerIndex = lines.indexOfFirst { it.contains("<- e04.mkv") }

            markerIndex shouldBe lines.indexOfFirst { it.contains("rus") } - 1
            output shouldNotContain "<- e01.mkv"
        }

        test("an outlier layout group names the files of every row, having no reference") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio")),
                probed("e02.mkv", t(0, "video"), t(1, "audio")),
                probed("e03.mkv", t(0, "video"), t(1, "subtitles"), t(2, "audio")),
            )

            render(infos, selection = withConfig) shouldContain "Layout 2"
        }
    }

    context("layout headers") {
        test("are omitted entirely when the batch is one layout") {
            render(listOf(probed("e01.mkv", t(0, "video")))) shouldNotContain "Layout 1"
        }

        test("name the shape and the episodes in each group") {
            val infos = (1..4).map { i ->
                probed(
                    "Show - S01E0$i - Title.mkv",
                    t(0, "video"),
                    t(1, if (i > 2) "subtitles" else "audio"),
                )
            }

            val output = render(infos, selection = withConfig)

            // Equal populations, so the tie breaks on the first file name.
            output shouldContain "*** Layout 1 (2 files - episodes 01-02): video, audio"
            output shouldContain "*** Layout 2 (2 files - episodes 03-04): video, subs"
        }

        test("fall back to a file list when the batch is not numbered") {
            val infos = listOf(
                probed("alpha.mkv", t(0, "video"), t(1, "audio")),
                probed("beta.mkv", t(0, "video"), t(1, "audio")),
                probed("gamma.mkv", t(0, "video"), t(1, "subtitles")),
            )

            val output = render(infos, selection = withConfig)

            output shouldContain "*** Layout 1 (2 files): video, audio"
            output shouldContain "      alpha.mkv"
        }

        test("carry the external labels, which the table below decodes into rows") {
            val infos = listOf(probed("e01.mkv", t(0, "video")), probed("e02.mkv", t(0, "video")))
            val externals = mapOf("e01.mkv" to mapOf("A/audio/mka" to ex("A")))

            val output = render(infos, externals = externals, selection = withConfig)

            output shouldContain "+ A"
            // The external file is a row in the table, identified by its label rather than by an id.
            output shouldContain "    A    audio"
        }
    }

    context("colour") {
        test("a guessed language is grayed as a whole padded cell") {
            val infos = listOf(probed("e01.mkv", t(0, "video")))
            val externals = mapOf("e01.mkv" to mapOf("A/audio/mka" to ex("A", guessed = true)))

            val output = render(infos, externals = externals, colour = true)

            // Whole pre-padded cell, so the escapes never count toward the column width — and the '?'
            // is composed here rather than carried, so the value the grouping compared was a bare "rus".
            output shouldContain "${ESC}[90mrus? ${ESC}[0m"
        }

        test("a differing cell wins over a guess, since varying is what the table is for") {
            val infos = listOf(probed("e01.mkv", t(0, "video")), probed("e02.mkv", t(0, "video")))
            val externals = mapOf(
                "e01.mkv" to mapOf("A/audio/mka" to ex("A", language = "rus", guessed = true)),
                "e02.mkv" to mapOf("A/audio/mka" to ex("A", language = "und", guessed = true)),
            )

            val output = render(infos, externals = externals, colour = true)

            output shouldContain "${ESC}[33mrus? ${ESC}[0m"
            output shouldNotContain "${ESC}[90mrus? ${ESC}[0m"
        }

        test("piped output carries no escapes at all, which is what the harness asserts against") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio", language = "jpn")),
                probed("e02.mkv", t(0, "video"), t(1, "audio", language = "rus")),
            )

            render(infos, selection = withConfig) shouldNotContain ESC
        }
    }

    context("verbose") {
        test("shows every file where the default truncates past eight") {
            // A minority is by definition smaller than its majority, so a list long enough to truncate
            // needs a big batch: 9 deviating files out of 20.
            val infos = (1..20).map { i ->
                probed("e%02d.mkv".format(i), t(0, "video"), t(1, "audio", language = if (i <= 9) "rus" else "jpn"))
            }

            render(infos, selection = withConfig) shouldContain "... and 1 more"
            render(infos, selection = withConfig, verbose = true) shouldNotContain "... and"
        }
    }
})

/** Built rather than typed, so no control character ever sits in the source. */
private val ESC = Char(27).toString()

// `render` lives in ProbeFixtures.kt, beside the probe records it consumes: three specs need it now.

