package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.CompanionType
import org.plukh.mkvtool.core.ConfiguredSource
import org.plukh.mkvtool.core.ExternalFile
import org.plukh.mkvtool.core.ExternalLeftovers
import org.plukh.mkvtool.core.ExternalLegend
import org.plukh.mkvtool.core.ExternalListing
import org.plukh.mkvtool.core.ExternalTrack
import org.plukh.mkvtool.core.FileIdentification
import org.plukh.mkvtool.core.LegendRow
import org.plukh.mkvtool.core.MatchTier
import org.plukh.mkvtool.core.ProbedTrack
import org.plukh.mkvtool.core.SourceListing
import org.plukh.mkvtool.core.StrictVerdict
import org.plukh.mkvtool.core.TrackListing
import org.plukh.mkvtool.core.VariantExternals
import org.plukh.mkvtool.core.VariantIdentity

/**
 * What an identify page actually looks like.
 *
 * The column grid, the absence glyphs and the blank lines are all asserted by substring in the Groovy
 * oracle, so they are pinned here character for character rather than described. Where a value could
 * plausibly be composed on either side of the seam, the spec says which side won and why.
 */
class InspectRendererTest : FunSpec({

    val esc = Char(27).toString()

    context("main track table") {
        test("the header is the identify grid, not the check report's narrower one") {
            val (out, _) = renderResult(FileIdentificationRenderer, identification(tracks = listOf(pt(0, "video"))))

            out shouldContain "  ID   TYPE       CODEC                  LANG  DEF  FOR  NAME"
        }

        test("a row lays out on that same grid") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(tracks = listOf(pt(1, "audio", codec = "AAC", language = "jpn", name = "Audio A"))),
            )

            out shouldContain "  1    audio      AAC                    jpn   no   no   Audio A"
        }

        test("an absent language is a question, not a blank") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(tracks = listOf(pt(0, "video", language = null))),
            )

            out shouldContain "  0    video      AVC                    ?     no   no"
        }

        test("'und' is printed as it stands — only an external file treats it as missing") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(tracks = listOf(pt(0, "video", language = "und"))),
            )

            out shouldContain "und"
        }

        test("an unreadable file says so and prints no table") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(listing = TrackListing.Unreadable("no tracks found")),
            )

            out shouldContain "  (mkvmerge could not identify this file: no tracks found)"
            out shouldNotContain "ID   TYPE"
        }

        test("a container holding nothing says that instead") {
            val (out, _) = renderResult(FileIdentificationRenderer, identification(tracks = emptyList()))

            out shouldContain "  (no tracks)"
        }
    }

    context("configured sources") {
        test("the resolved path heads the block and an absent one is stated, never fatal") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(
                    tracks = listOf(pt(0, "video")),
                    sources = listOf(ConfiguredSource("Show.rus.mka", SourceListing.Missing)),
                ),
            )

            out shouldContain "  + Show.rus.mka"
            out shouldContain "    (not found)"
        }

        test("an unreadable source reports mkvmerge's reason") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(
                    tracks = listOf(pt(0, "video")),
                    sources = listOf(ConfiguredSource("x.mka", SourceListing.Unreadable("broken"))),
                ),
            )

            out shouldContain "    (mkvmerge could not identify this file: broken)"
        }

        test("its language falls back to '-', since a raw subtitle file carries none at all") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(
                    tracks = listOf(pt(0, "video")),
                    sources = listOf(
                        ConfiguredSource("x.ass", SourceListing.Tracks(listOf(pt(0, "subtitles", "SSA", null)))),
                    ),
                ),
            )

            out shouldContain "  0    subtitles  SSA                    -     no   no"
        }
    }

    context("discovered externals") {
        test("one block per variant, with the kinds of file it holds named in the header") {
            val (out, _) = renderResult(FileIdentificationRenderer, identification(tracks = listOf(pt(0, "video")), externals = listOf(mergedVariant())))

            out shouldContain "  + [A] [GroupA] (.mka, .ass)"
        }

        test("an unprobed file shows track id 0 — the id an additionalSources entry must name") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(tracks = listOf(pt(0, "video")), externals = listOf(variantOf(unprobedAss()))),
            )

            // The Groovy case `120_unreadable_external_reported` asserts the *negative* of this exact
            // string, so a truncated file is never dressed up as a healthy track.
            out shouldContain "  0    subtitles  ASS                    rus?"
        }

        test("the renderer composes the '?', which the model does not carry") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(tracks = listOf(pt(0, "video")), externals = listOf(variantOf(unprobedAss()))),
            )

            out shouldContain "rus?"
            out shouldNotContain esc
        }

        test("with colour on, the gray wraps the whole padded cell and nothing inside it") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(tracks = listOf(pt(0, "video")), externals = listOf(variantOf(unprobedAss()))),
                colorEnabled = true,
            )

            // The cell is padded first, so the escape never lands between the value and its padding.
            out shouldContain "$esc[90mrus? $esc[0m"
        }

        test("a language the file itself carries is never grayed") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(
                    tracks = listOf(pt(0, "video")),
                    externals = listOf(variantOf(probedMka(language = "jpn", guessed = false))),
                ),
                colorEnabled = true,
            )

            out shouldNotContain "$esc[90mjpn"
        }

        test("an external row is stripped of trailing blanks, unlike the printf tables above it") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(tracks = listOf(pt(0, "video")), externals = listOf(variantOf(unprobedAss()))),
            )

            // NAME is last and unpadded, so an unnamed track would leave a line of nothing but blanks.
            out.lines().first { it.contains("ASS") }.let { it shouldBe it.trimEnd() }
            // The main table does not strip, so its unnamed video row keeps its trailing padding.
            out.lines().first { it.contains("AVC") }.endsWith(" ") shouldBe true
        }

        test("an unprobed file's flags are unknown, which is not the same as 'no'") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(tracks = listOf(pt(0, "video")), externals = listOf(variantOf(unprobedAss()))),
            )

            out shouldContain "rus?  -    -"
        }

        test("an episode-number match names the file, since its name is all that ties it to the episode") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(
                    tracks = listOf(pt(0, "video")),
                    externals = listOf(variantOf(unprobedAss(tier = MatchTier.EPISODE))),
                ),
            )

            out shouldContain "(episode match: 01.ass)"
        }

        test("a file mkvmerge could not read is stated, not dressed as a healthy track") {
            val (out, _) = renderResult(
                FileIdentificationRenderer,
                identification(
                    tracks = listOf(pt(0, "video")),
                    externals = listOf(
                        variantOf(
                            ExternalFile("Rus/01.mka", "mka", MatchTier.NAME, ExternalListing.Unreadable("bad")),
                        ),
                    ),
                ),
            )

            out shouldContain "    (mkvmerge could not read this file: bad)"
            out shouldNotContain "0    audio      Matroska"
        }
    }

    context("the legend") {
        test("names every variant, its pattern and its file count") {
            val (out, _) = renderResult(
                ExternalLegendRenderer,
                ExternalLegend(listOf(legendRow(leaf = "[GroupA]", dir = "Rus sound/[GroupA]"))),
            )

            out shouldContain "*** External files: 1 variant discovered"
            // VARIANT sits at its 12-wide floor; PATTERN is sized to its longest entry (29 here).
            out shouldContain "  LBL  TYPE       VARIANT      PATTERN                       FILES"
            out shouldContain "  A    audio      [GroupA]     Rus sound/[GroupA]/<name>.mka 2"
        }

        test("a merged variant counts once but lists both its kinds") {
            val rows = listOf(
                legendRow(leaf = "[GroupA]", dir = "Rus sound/[GroupA]"),
                legendRow(leaf = "[GroupA]", dir = "Rus subs/[GroupA]", type = CompanionType.SUBTITLES, ext = "ass"),
            )

            val (out, _) = renderResult(ExternalLegendRenderer, ExternalLegend(rows))

            out shouldContain "1 variant discovered"
            out shouldContain "Rus sound/[GroupA]/<name>.mka"
            out shouldContain "Rus subs/[GroupA]/<name>.ass"
        }

        test("a section with no suffix anywhere can only describe itself by episode number") {
            val (out, _) = renderResult(
                ExternalLegendRenderer,
                ExternalLegend(listOf(legendRow(leaf = "Rus", dir = "Rus", suffix = null))),
            )

            out shouldContain "Rus/<episode number>.mka"
        }

        test("several extensions in one section are joined") {
            val (out, _) = renderResult(
                ExternalLegendRenderer,
                ExternalLegend(
                    listOf(legendRow(leaf = "Subs", dir = "Subs", type = CompanionType.SUBTITLES, ext = "ass", extras = listOf("srt"))),
                ),
            )

            out shouldContain "Subs/<name>.ass/srt"
        }

        test("nothing discovered prints nothing at all") {
            val (out, _) = renderResult(ExternalLegendRenderer, ExternalLegend(emptyList()))

            out shouldBe ""
        }

        context("the variant's display name") {
            test("a suffix qualifies the directory it sits in") {
                variantDisplayName(identity(leaf = "[GroupA]", suffix = ".rus")) shouldBe "[GroupA] rus"
            }

            test("a directory alone names itself") {
                variantDisplayName(identity(leaf = "Rus sound", suffix = null)) shouldBe "Rus sound"
            }

            test("a top-level suffix stands on its own") {
                variantDisplayName(identity(leaf = null, suffix = ".rus")) shouldBe "rus"
            }

            test("a collision variant is named by its whole path, since its leaf names two things") {
                variantDisplayName(
                    identity(leaf = "[Grp]", suffix = null, dirRel = "Rus sound/[Grp]", collision = true),
                ) shouldBe "Rus sound/[Grp]"
            }

            test("neither directory nor suffix leaves only the match itself to report") {
                variantDisplayName(identity(leaf = null, suffix = null)) shouldBe "(same name)"
            }
        }
    }

    context("leftovers") {
        test("unmatched files are named under their own header") {
            val (out, _) = renderResult(ExternalLeftoversRenderer, ExternalLeftovers(listOf("Bonus.ass"), emptyList()))

            out shouldContain "*** Unmatched external files (1)"
            out shouldContain "      Bonus.ass"
        }

        test("main-type files in subdirectories are reported but never scanned") {
            val (out, _) = renderResult(
                ExternalLeftoversRenderer,
                ExternalLeftovers(emptyList(), listOf("extras/Sample.mkv")),
            )

            out shouldContain "*** Extras: 1 file of a main type in subdirectories, not scanned as sources"
            out shouldContain "      extras/Sample.mkv"
        }

        test("a long list is truncated") {
            val many = (1..12).map { "file$it.ass" }

            val (out, _) = renderResult(ExternalLeftoversRenderer, ExternalLeftovers(many, emptyList()))

            out shouldContain "      ... and 4 more"
        }

        test("nothing left over prints nothing") {
            val (out, _) = renderResult(ExternalLeftoversRenderer, ExternalLeftovers(emptyList(), emptyList()))

            out shouldBe ""
        }
    }

    context("the strict verdict") {
        test("both reasons can print, in red, on stderr") {
            val (out, err) = renderResult(StrictVerdictRenderer, StrictVerdict(2, 1), colorEnabled = true)

            out shouldBe ""
            err shouldContain "$esc[31m*** Strict mode: 2 discrepancies affecting selected tracks.$esc[0m"
            err shouldContain "1 config problem (the report above was not classified against a config)."
        }

        test("one discrepancy is singular") {
            val (_, err) = renderResult(StrictVerdictRenderer, StrictVerdict(1, 0))

            err shouldContain "1 discrepancy affecting selected tracks."
        }

        test("nothing to say prints nothing") {
            val (out, err) = renderResult(StrictVerdictRenderer, StrictVerdict(0, 0))

            out shouldBe ""
            err shouldBe ""
        }
    }

    test("a whole file block, blank lines included") {
        val (out, _) = renderResult(
            FileIdentificationRenderer,
            identification(
                tracks = listOf(pt(0, "video")),
                sources = listOf(ConfiguredSource("x.mka", SourceListing.Missing)),
                externals = listOf(variantOf(unprobedAss())),
            ),
        )

        // One blank line before each block and one after the file — never one per block, which made six
        // externals fill a screen.
        out shouldBe listOf(
            "*** a.mkv",
            "  ID   TYPE       CODEC                  LANG  DEF  FOR  NAME",
            "  0    video      AVC                    eng   no   no   ",
            "",
            "  + x.mka",
            "    (not found)",
            "",
            "  + [A] [GroupA] (.ass)",
            "  0    subtitles  ASS                    rus?  -    -",
            "",
        ).joinToString(System.lineSeparator(), postfix = System.lineSeparator())
    }
})

private fun identification(
    listing: TrackListing? = null,
    tracks: List<ProbedTrack> = emptyList(),
    sources: List<ConfiguredSource> = emptyList(),
    externals: List<VariantExternals> = emptyList(),
) = FileIdentification("a.mkv", listing ?: TrackListing.Tracks(tracks), sources, externals)

private fun pt(
    id: Int,
    type: String,
    codec: String = "AVC",
    language: String? = "eng",
    name: String = "",
) = ProbedTrack(id, type, codec, "A_$codec", language, name, default = false, forced = false)

private fun identity(
    leaf: String?,
    suffix: String?,
    dirRel: String = leaf.orEmpty(),
    collision: Boolean = false,
    label: String = "A",
) = VariantIdentity(label, leaf, suffix, dirRel, collision)

private fun variantOf(vararg files: ExternalFile) =
    VariantExternals(identity(leaf = "[GroupA]", suffix = null), files.toList())

private fun mergedVariant() = variantOf(
    ExternalFile("Rus sound/[GroupA]/01.mka", "mka", MatchTier.NAME, ExternalListing.Tracks(emptyList())),
    unprobedAss(),
)

private fun unprobedAss(tier: MatchTier = MatchTier.NAME) = ExternalFile(
    relPath = "Rus subs/[GroupA]/01.ass",
    extension = "ass",
    tier = tier,
    listing = ExternalListing.Tracks(
        listOf(ExternalTrack(0, "subtitles", "ASS", "rus", guessed = true, default = null, forced = null, name = "")),
    ),
)

private fun probedMka(language: String, guessed: Boolean) = ExternalFile(
    relPath = "Rus sound/[GroupA]/01.mka",
    extension = "mka",
    tier = MatchTier.NAME,
    listing = ExternalListing.Tracks(
        listOf(ExternalTrack(0, "audio", "Matroska", language, guessed, default = false, forced = false, name = "")),
    ),
)

private fun legendRow(
    leaf: String,
    dir: String?,
    suffix: String? = "",
    type: CompanionType = CompanionType.AUDIO,
    ext: String = "mka",
    extras: List<String> = emptyList(),
    fileCount: Int = 2,
) = LegendRow(identity(leaf = leaf, suffix = suffix), type, dir, suffix, listOf(ext) + extras, fileCount)
