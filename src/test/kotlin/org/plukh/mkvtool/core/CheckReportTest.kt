package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * The consistency check's *answer*, asserted on the model — grouping, classification and the blocking
 * count. How it reads is `CheckReportRendererTest`'s job.
 *
 * The report as a whole was also verified differentially: eleven scenarios (uniform, value splits, two
 * layouts, externals, duplicates, chapters, unreadable files, verbose, wide names, all-unreadable) were
 * run through v1's `runConsistencyCheck` and this model plus its renderer, and the text came out
 * byte-identical.
 */
class CheckReportTest : FunSpec({

    val withConfig = TrackSelection(
        hasConfig = true,
        videoIds = setOf(0),
        audioIds = setOf(1),
        subtitleIds = setOf(4),
        titleById = mapOf(1 to "Japanese"),
    )

    context("layout grouping") {
        test("groups by layout, largest first, ties broken by file name") {
            val infos = listOf(
                probed("z.mkv", t(0, "video"), t(1, "audio")),
                probed("a.mkv", t(0, "video"), t(1, "subtitles")),
                probed("b.mkv", t(0, "video"), t(1, "audio")),
                probed("c.mkv", t(0, "video"), t(1, "subtitles")),
                probed("d.mkv", t(0, "video"), t(1, "subtitles")),
            )

            val report = buildCheckReport(infos)

            report.layouts.map { it.fileNames } shouldContainExactly listOf(
                listOf("a.mkv", "c.mkv", "d.mkv"),
                listOf("z.mkv", "b.mkv"),
            )
        }

        test("a uniform batch is one layout with no findings") {
            val infos = (1..3).map { probed("e0$it.mkv", t(0, "video"), t(1, "audio")) }

            val report = buildCheckReport(infos)

            report.layouts shouldHaveSize 1
            report.findings.shouldBeEmpty()
            report.blockingCount shouldBe 0
        }

        test("externals split a layout even when the tracks are identical") {
            // The group count answers "how many muxing passes", and a season whose dubs arrive at
            // different episodes is not one job however uniform its .mkv files are.
            val a = probed("e01.mkv", t(0, "video"))
            val b = probed("e02.mkv", t(0, "video"))
            val externals = mapOf(a.file.name to mapOf("A/audio/mka" to ex("A")))

            val report = buildCheckReport(listOf(a, b), { externals[it.file.name] ?: emptyMap() })

            report.layouts shouldHaveSize 2
            report.hasExternals shouldBe true
            val finding = report.findings.single().shouldBeInstanceOf<LayoutFinding>()
            finding.externalsDiffer shouldBe true
            finding.internalDiffers shouldBe false
            finding.blocking shouldBe false
        }

        test("episode labels come from the group's own files") {
            val infos = (1..4).map { i ->
                probed("Show - S01E0$i - Title.mkv", t(0, "video"), t(1, if (i > 2) "subtitles" else "audio"))
            }

            val report = buildCheckReport(infos)

            // Equal populations, so the tie breaks on the first file name: E01's group comes first.
            report.layouts.map { it.episodeLabels } shouldContainExactly
                listOf(listOf("01", "02"), listOf("03", "04"))
        }

        test("an unnumbered batch has no episode labels, so the renderer falls back to file names") {
            val report = buildCheckReport(listOf(probed("odd name.mkv", t(0, "video"))))

            report.layouts.single().episodeLabels shouldBe null
        }
    }

    context("classification") {
        test("a layout outlier at a selected id blocks") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio")),
                probed("e02.mkv", t(0, "video"), t(1, "audio")),
                probed("e03.mkv", t(0, "video"), t(1, "subtitles")),
            )

            val report = buildCheckReport(infos, selection = withConfig)

            val finding = report.findings.single().shouldBeInstanceOf<LayoutFinding>()
            finding.affectedIds shouldContainExactly listOf(1)
            finding.blocking shouldBe true
            report.blockingCount shouldBe 1
        }

        test("a layout outlier away from every selected id is informational") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio"), t(2, "subtitles")),
                probed("e02.mkv", t(0, "video"), t(1, "audio"), t(2, "subtitles")),
                probed("e03.mkv", t(0, "video"), t(1, "audio")),
            )

            val report = buildCheckReport(infos, selection = withConfig)

            report.findings.single().blocking shouldBe false
            report.blockingCount shouldBe 0
        }

        test("a value split at a selected id blocks when that type is not copied wholesale") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio", language = "jpn"), t(2, "audio")),
                probed("e02.mkv", t(0, "video"), t(1, "audio", language = "rus"), t(2, "audio")),
            )

            val report = buildCheckReport(infos, selection = withConfig)

            val finding = report.findings.single().shouldBeInstanceOf<TrackValueFinding>()
            finding.id shouldBe 1
            finding.configTitle shouldBe "Japanese"
            finding.varying shouldContainExactly listOf(SignatureField.LANGUAGE)
            finding.blocking shouldBe true
        }

        test("the same split is informational when every track of the type is copied") {
            // If all of them are being copied, ids cannot select the wrong thing however they shift.
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio", language = "jpn")),
                probed("e02.mkv", t(0, "video"), t(1, "audio", language = "rus")),
            )

            val report = buildCheckReport(infos, selection = withConfig)

            report.findings.single().blocking shouldBe false
        }

        test("without a config nothing can block") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), t(1, "audio", language = "jpn")),
                probed("e02.mkv", t(0, "video"), t(1, "audio", language = "rus")),
            )

            val report = buildCheckReport(infos, selection = TrackSelection.NONE)

            report.hasConfig shouldBe false
            report.findings shouldHaveSize 1
            report.blockingCount shouldBe 0
        }

        test("an external value split is reported and never blocks") {
            // Nothing selects an external file by id, so this cannot mux the wrong track — but a dub
            // tagged Russian for half a season is the same class of surprise as an internal split.
            val a = probed("e01.mkv", t(0, "video"))
            val b = probed("e02.mkv", t(0, "video"))
            val externals = mapOf(
                a.file.name to mapOf("A/audio/mka" to ex("A", language = "rus")),
                b.file.name to mapOf("A/audio/mka" to ex("A", language = "und")),
            )

            val report = buildCheckReport(listOf(a, b), { externals.getValue(it.file.name) }, withConfig)

            val finding = report.findings.single().shouldBeInstanceOf<ExternalValueFinding>()
            finding.variant.label shouldBe "A"
            finding.varying shouldContainExactly listOf(SignatureField.LANGUAGE)
            finding.blocking shouldBe false
        }

        test("ambiguous tracks block only when the config selects one of them") {
            val infos = listOf(
                probed(
                    "e01.mkv",
                    t(0, "video"),
                    t(1, "audio", codec = "AC-3", language = "eng"),
                    t(2, "audio", codec = "AC-3", language = "eng"),
                )
            )

            val selected = buildCheckReport(infos, selection = withConfig)
                .findings.filterIsInstance<AmbiguousTracksFinding>().single()
            selected.ids shouldContainExactly listOf(1, 2)
            selected.selectedIds shouldContainExactly listOf(1)
            selected.blocking shouldBe true

            val unselected = buildCheckReport(infos, selection = TrackSelection(hasConfig = true))
                .findings.filterIsInstance<AmbiguousTracksFinding>().single()
            unselected.selectedIds.shouldBeEmpty()
            unselected.blocking shouldBe false
        }

        test("a chapter split is an observation across the whole batch") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), chapters = 4),
                probed("e02.mkv", t(0, "video"), chapters = 0),
            )

            val report = buildCheckReport(infos)

            report.chapters!!.withChapters shouldContainExactly listOf("e01.mkv")
            report.chapters!!.withoutChapters shouldContainExactly listOf("e02.mkv")
            report.findings.single() shouldBe ChapterFinding
        }

        test("chapters everywhere or nowhere is not a finding") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video"), chapters = 4),
                probed("e02.mkv", t(0, "video"), chapters = 2),
            )

            buildCheckReport(infos).chapters shouldBe null
        }
    }

    context("unreadable files") {
        test("are excluded from the comparison but kept with their reason") {
            val infos = listOf(
                probed("e01.mkv", t(0, "video")),
                ProbeResult.Failed(File("broken.mkv"), "not recognised as a media file"),
            )

            val report = buildCheckReport(infos)

            report.readable shouldContainExactly listOf("e01.mkv")
            report.unreadable.single().fileName shouldBe "broken.mkv"
            report.unreadable.single().reason shouldBe "not recognised as a media file"
            report.layouts shouldHaveSize 1
        }

        test("a batch with nothing readable yields no layouts and no findings") {
            val report = buildCheckReport(listOf(ProbeResult.Failed(File("x.mkv"), "mkvmerge exit 2")))

            report.readable.shouldBeEmpty()
            report.layouts.shouldBeEmpty()
            report.findings.shouldBeEmpty()
            report.blockingCount shouldBe 0
        }
    }

    context("video names") {
        test("are carried per id for the renderer, distinct and in file order") {
            // They are deliberately outside the signature — a video title routinely differs per episode —
            // so the model carries them separately rather than letting them split a group.
            val infos = listOf(
                probed("e01.mkv", t(0, "video", name = "First")),
                probed("e02.mkv", t(0, "video", name = "Second")),
                probed("e03.mkv", t(0, "video", name = "First")),
            )

            val report = buildCheckReport(infos)

            report.layouts shouldHaveSize 1
            report.layouts.single().videoNamesById.getValue(0) shouldContainExactly listOf("First", "Second")
        }
    }
})

private fun t(
    id: Int,
    type: String,
    codec: String = "AAC",
    language: String = "eng",
    name: String = "",
    default: Boolean = false,
    forced: Boolean = false,
): ProbedTrack = ProbedTrack(id, type, codec, "ID", language, name, default, forced)

private fun probed(fileName: String, vararg tracks: ProbedTrack, chapters: Int = 0): ProbeResult.Probed =
    ProbeResult.Probed(
        file = File(fileName),
        allTracks = tracks.toList(),
        tracks = tracks.associate {
            it.id to TrackSlot(it.id, signatureOf(it), if (it.type == "video") (it.trackName ?: "") else null)
        },
        chapters = chapters,
    )

private fun ex(label: String, language: String = "rus", guessed: Boolean = false): ExternalSlot = ExternalSlot(
    key = "$label/audio/mka",
    signature = TrackSignature("audio", "Matroska", language, "", default = false, forced = false),
    guessed = guessed,
    variant = VariantIdentity(label, leaf = "[Group$label]", suffix = null, dirRel = "Rus sound/[Group$label]", collision = false),
)
