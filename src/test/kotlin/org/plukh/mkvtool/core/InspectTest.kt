package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.plukh.mkvtool.out.Advisory
import java.io.File

/**
 * The inspection spine, in-process. No mkvmerge: the prober is a lambda, which is also what lets the
 * cache invariants be asserted by counting the calls it received.
 *
 * The rendered form of all this is `InspectRendererTest`; what is pinned here is the model, and above all
 * the four rules that decide what an external file *is* said to hold — probed-field-wins, `und` counting
 * as missing, the guess marker, and the synthetic row for a file nothing read.
 */
class InspectTest : FunSpec({

    context("empty batch") {
        test("says so, probes nothing and reports an empty document") {
            val dir = tempdir()
            File(dir, "notes.txt").writeText("x")
            var probes = 0

            val report = inspectDirectory(dir, options(), SilentRenderer) { probes++; failed(it) }

            probes shouldBe 0
            report.mediaFiles.shouldBeEmpty()
            report.files.shouldBeEmpty()
            report.legend.rows.shouldBeEmpty()
            report.leftovers.isEmpty shouldBe true
        }

        test("a mask that matched nothing is reported as such, not as an empty directory") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")
            val recorder = RecordingRenderer()

            inspectDirectory(dir, options(fileMasks = listOf("*.mp4")), recorder) { probedResult(it) }

            recorder.events.filterIsInstance<Advisory>().map { it.text } shouldContainExactly
                listOf("*** No media files match: *.mp4")
        }

        test("with no masks it names the extensions it was looking for, sorted") {
            val dir = tempdir()
            val recorder = RecordingRenderer()

            inspectDirectory(dir, options(), recorder) { probedResult(it) }

            recorder.events.filterIsInstance<Advisory>().map { it.text } shouldContainExactly
                listOf(
                    "*** No media files (avi, m2ts, m4v, mka, mks, mkv, mov, mp4, ts, webm) " +
                        "in the current directory",
                )
        }
    }

    context("main track listing") {
        test("an unreadable file carries mkvmerge's own reason") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")

            val report = inspectDirectory(dir, options(identify = true), SilentRenderer) {
                ProbeResult.Failed(it, "no tracks found")
            }

            report.files.single().listing shouldBe TrackListing.Unreadable("no tracks found")
        }

        test("a container with no tracks is an empty listing, not a failure") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")

            val report = inspectDirectory(dir, options(identify = true), SilentRenderer) { probedResult(it) }

            report.files.single().listing shouldBe TrackListing.Tracks(emptyList())
        }

        test("tracks are kept in mkvmerge's own order") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")

            val report = inspectDirectory(dir, options(identify = true), SilentRenderer) {
                probedResult(it, track(0, "video"), track(2, "audio"), track(1, "subtitles"))
            }

            val listing = report.files.single().listing.shouldBeInstanceOf<TrackListing.Tracks>()
            listing.tracks.map { it.id } shouldContainExactly listOf(0, 2, 1)
        }

        test("a check-only run computes no identifications at all") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")

            val report = inspectDirectory(dir, options(identify = false), SilentRenderer) { probedResult(it) }

            report.files.shouldBeEmpty()
            report.mediaFiles shouldContainExactly listOf("a.mkv")
        }
    }

    context("configured sources") {
        test("the template is resolved per episode and the raw string is what is reported") {
            val dir = tempdir()
            File(dir, "Show - S01E01 - Title.mkv").writeText("x")
            File(dir, "Show - S01E01 - Title.rus.srt").writeText("x")

            val report = inspectDirectory(
                dir,
                options(identify = true, config = configWithSource("\${fileName}.rus.srt")),
                SilentRenderer,
            ) { probedResult(it, track(0, "audio")) }

            val source = report.files.single().configuredSources.single()
            source.path shouldBe "Show - S01E01 - Title.rus.srt"
            source.listing.shouldBeInstanceOf<SourceListing.Tracks>()
        }

        test("a source that is not on disk is missing, never fatal") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")

            val report = inspectDirectory(
                dir,
                options(identify = true, config = configWithSource("\${fileName}.rus.srt")),
                SilentRenderer,
            ) { probedResult(it) }

            report.files.single().configuredSources.single().listing shouldBe SourceListing.Missing
        }

        test("a source mkvmerge cannot read reports its reason") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")
            File(dir, "a.rus.srt").writeText("x")

            val report = inspectDirectory(
                dir,
                options(identify = true, config = configWithSource("\${fileName}.rus.srt")),
                SilentRenderer,
            ) { file -> if (file.name.endsWith(".srt")) ProbeResult.Failed(file, "broken") else probedResult(file) }

            report.files.single().configuredSources.single().listing shouldBe
                SourceListing.Unreadable("broken")
        }

        test("a check-only run resolves none of them, so it costs no probe") {
            val dir = tempdir()
            File(dir, "a.mkv").writeText("x")
            File(dir, "a.rus.srt").writeText("x")
            var probes = 0

            inspectDirectory(
                dir,
                options(identify = false, config = configWithSource("\${fileName}.rus.srt")),
                SilentRenderer,
            ) { probes++; probedResult(it) }

            // It is a discovered companion of `a.mkv`, but `.srt` is not worth a subprocess and nothing
            // resolved it as a source either, so the only probe is the main file itself.
            probes shouldBe 1
        }
    }

    context("external rows") {
        test("a probed value wins field by field") {
            val listing = externalRowsOf(
                "mka", languageGuess = "rus",
                probed = probedResult(File("x.mka"), track(0, "audio", codec = "Matroska", language = "jpn")),
            )

            val row = listing.shouldBeInstanceOf<ExternalListing.Tracks>().tracks.single()
            row.language shouldBe "jpn"
            row.guessed shouldBe false
            row.codec shouldBe "Matroska"
        }

        test("'und' counts as missing, so the folder's guess wins it") {
            // Matroska has no other way to say "untagged", and an untagged dub is the common case here.
            val listing = externalRowsOf(
                "mka", languageGuess = "rus",
                probed = probedResult(File("x.mka"), track(0, "audio", language = "und")),
            )

            val row = listing.shouldBeInstanceOf<ExternalListing.Tracks>().tracks.single()
            row.language shouldBe "rus"
            row.guessed shouldBe true
        }

        test("a guess is the language itself; that it was guessed is a separate fact") {
            // Not "rus?" — the marker is the renderer's. A guessed rus and a tagged rus are the same
            // language, so a directory tagged only halfway through a season must not read as two values.
            val listing = externalRowsOf("ass", languageGuess = "rus", probed = null)

            val row = listing.shouldBeInstanceOf<ExternalListing.Tracks>().tracks.single()
            row.language shouldBe "rus"
            row.guessed shouldBe true
        }

        test("a tagged file and a guessed one agree on the language, differing only in provenance") {
            // The mixed directory: some files tagged, some `und`. Both say rus, which is what stops the
            // check reporting a difference that is not one.
            val tagged = externalRowsOf(
                "mka", "rus", probedResult(File("a.mka"), track(0, "audio", language = "rus")),
            ).shouldBeInstanceOf<ExternalListing.Tracks>().tracks.single()
            val untagged = externalRowsOf(
                "mka", "rus", probedResult(File("b.mka"), track(0, "audio", language = "und")),
            ).shouldBeInstanceOf<ExternalListing.Tracks>().tracks.single()

            tagged.language shouldBe untagged.language
            tagged.guessed shouldBe false
            untagged.guessed shouldBe true

            // A genuine mis-tag still differs, because the languages themselves do.
            val misTagged = externalRowsOf(
                "mka", "rus", probedResult(File("c.mka"), track(0, "audio", language = "jpn")),
            ).shouldBeInstanceOf<ExternalListing.Tracks>().tracks.single()
            (misTagged.language == tagged.language) shouldBe false
        }

        test("no tag and no guess leaves nothing to say") {
            val listing = externalRowsOf("ass", languageGuess = null, probed = null)

            val row = listing.shouldBeInstanceOf<ExternalListing.Tracks>().tracks.single()
            row.language.shouldBeNull()
            row.guessed shouldBe false
        }

        test("an unprobed file gets track id 0 — the id an additionalSources entry must name") {
            val listing = externalRowsOf("ass", languageGuess = null, probed = null)

            val row = listing.shouldBeInstanceOf<ExternalListing.Tracks>().tracks.single()
            row.id shouldBe 0
            row.type shouldBe "subtitles"
            row.codec shouldBe "ASS"
            // Not "no": nothing read them.
            row.default.shouldBeNull()
            row.forced.shouldBeNull()
        }

        test("an unprobed file's codec comes from its extension, uppercased when unlisted") {
            fun codecOf(ext: String) = externalRowsOf(ext, null, null)
                .shouldBeInstanceOf<ExternalListing.Tracks>().tracks.single().codec

            codecOf("mka") shouldBe "Matroska"
            codecOf("idx") shouldBe "VobSub"
            codecOf("sub") shouldBe "VobSub"
            codecOf("opus") shouldBe "OPUS"
        }

        test("a file mkvmerge could not read is said outright, not dressed as a healthy track") {
            val listing = externalRowsOf("mka", "rus", ProbeResult.Failed(File("x.mka"), "not matroska"))

            listing shouldBe ExternalListing.Unreadable("not matroska")
        }

        test("a probed file holding nothing is an empty listing") {
            val listing = externalRowsOf("mka", "rus", probedResult(File("x.mka")))

            listing shouldBe ExternalListing.Tracks(emptyList())
        }

        test("a missing type or codec falls back, Groovy-truthily — empty counts as missing") {
            val listing = externalRowsOf(
                "mka", null,
                probedResult(File("x.mka"), ProbedTrack(0, "", "", null, "eng", "", false, false)),
            )

            val row = listing.shouldBeInstanceOf<ExternalListing.Tracks>().tracks.single()
            row.type shouldBe "?"
            row.codec shouldBe "?"
        }
    }

    context("discovery integration") {
        test("only probe-worthy extensions are ever read") {
            val dir = tempdir()
            File(dir, "Show - S01E01 - Title.mkv").writeText("x")
            File(dir, "Subs").mkdirs()
            File(dir, "Subs/Show - S01E01 - Title.ass").writeText("x")
            File(dir, "Subs/Show - S01E01 - Title.srt").writeText("x")
            val probed = mutableListOf<String>()

            inspectDirectory(dir, options(identify = true), SilentRenderer) {
                probed += it.name
                probedResult(it)
            }

            probed shouldContainExactly listOf("Show - S01E01 - Title.mkv")
        }

        test("matching runs pre-mask, so a mask does not turn the rest of the season into leftovers") {
            val dir = tempdir()
            listOf("01", "02").forEach { ep ->
                File(dir, "Show - S01E$ep - Title.mkv").writeText("x")
                File(dir, "Rus").mkdirs()
                File(dir, "Rus/Show - S01E$ep - Title.mka").writeText("x")
            }

            val report = inspectDirectory(
                dir,
                options(identify = true, fileMasks = listOf("Show - S01E01 - Title.mkv")),
                SilentRenderer,
            ) { probedResult(it, track(0, "audio")) }

            report.mediaFiles shouldContainExactly listOf("Show - S01E01 - Title.mkv")
            // E02's dub was matched to E02 and so is not unmatched — it is simply not displayed.
            report.leftovers.unmatched.shouldBeEmpty()
            report.legend.rows.single().fileCount shouldBe 2
        }

        test("the output directory is excluded, so muxed files are not externals of themselves") {
            val dir = tempdir()
            File(dir, "Show - S01E01 - Title.mkv").writeText("x")
            File(dir, "mkv").mkdirs()
            File(dir, "mkv/Show - S01E01 - Title.mka").writeText("x")

            val report = inspectDirectory(
                dir,
                options(identify = true, config = Config(general = GeneralConfig(destinationDir = "mkv"))),
                SilentRenderer,
            ) { probedResult(it) }

            report.legend.rows.shouldBeEmpty()
            report.files.single().externals.shouldBeEmpty()
        }

        test("a merged variant is one block holding both its kinds of file") {
            val dir = tempdir()
            File(dir, "Show - S01E01 - Title.mkv").writeText("x")
            listOf("Rus sound", "Rus subs").forEach { File(dir, "$it/[GroupA]").mkdirs() }
            File(dir, "Rus sound/[GroupA]/Show - S01E01 - Title.mka").writeText("x")
            File(dir, "Rus subs/[GroupA]/Show - S01E01 - Title.ass").writeText("x")

            val report = inspectDirectory(dir, options(identify = true), SilentRenderer) {
                probedResult(it, track(0, "audio"))
            }

            val variant = report.files.single().externals.single()
            variant.files shouldHaveSize 2
            variant.extensions shouldContainExactly listOf("mka", "ass")
            // One label, two sections in the legend: the merge is a display fact, not a grouping one.
            report.legend.variantCount shouldBe 1
            report.legend.rows shouldHaveSize 2
        }

        test("leftovers name what belongs to nothing") {
            val dir = tempdir()
            File(dir, "Show - S01E01 - Title.mkv").writeText("x")
            File(dir, "Bonus.ass").writeText("x")
            File(dir, "extras").mkdirs()
            File(dir, "extras/Sample.mkv").writeText("x")

            val report = inspectDirectory(dir, options(identify = true), SilentRenderer) { probedResult(it) }

            report.leftovers.unmatched shouldContainExactly listOf("Bonus.ass")
            report.leftovers.extras shouldContainExactly listOf("extras/Sample.mkv")
        }
    }

    context("external slots") {
        test("a half-tagged dub directory is one consistent slot, not two values") {
            // Ten files in `Rus sound`, five tagged `rus` and five `und` — a group that started tagging
            // mid-season. The guess is per variant and the tag per file, so this mixture is ordinary, and
            // every one of the files is Russian. Reporting it as `language differs` would point the reader
            // at a non-problem.
            val tagged = variantExternals(language = "rus", guessed = false)
            val untagged = variantExternals(language = "rus", guessed = true)

            val a = externalSlotsFor(listOf(tagged)).getValue("A/audio/mka")
            val b = externalSlotsFor(listOf(untagged)).getValue("A/audio/mka")

            a.signature shouldBe b.signature
            a.guessed shouldBe false
            b.guessed shouldBe true
        }

        test("a genuine mis-tag still differs, because the languages themselves do") {
            val russian = externalSlotsFor(listOf(variantExternals(language = "rus", guessed = true)))
            val japanese = externalSlotsFor(listOf(variantExternals(language = "jpn", guessed = false)))

            russian.getValue("A/audio/mka").signature shouldNotBe japanese.getValue("A/audio/mka").signature
        }

        test("the key carries the extension, so one variant's .ass and .srt do not collide") {
            // A group shipping both for one episode and only the .ass for the next is two slots, which is
            // what keeps the two episodes in different muxing groups.
            val variant = VariantExternals(
                identityA,
                listOf(externalFile("ass", "rus", guessed = true), externalFile("srt", "rus", guessed = true)),
            )

            externalSlotsFor(listOf(variant)).keys.toList() shouldContainExactly
                listOf("A/subtitles/ass", "A/subtitles/srt")
        }

        test("an unreadable file still occupies its slot, described by its extension") {
            val variant = VariantExternals(
                identityA,
                listOf(ExternalFile("Rus/01.mka", "mka", MatchTier.NAME, ExternalListing.Unreadable("bad"))),
            )

            val slot = externalSlotsFor(listOf(variant)).getValue("A/audio/mka")
            slot.signature.type shouldBe "audio"
            slot.signature.codec shouldBe "Matroska"
            slot.signature.language shouldBe "-"
        }
    }

    context("probe caching") {
        test("nothing is probed twice, and nothing is probed after the meter says it has finished") {
            val dir = tempdir()
            File(dir, "Show - S01E01 - Title.mkv").writeText("x")
            File(dir, "Rus").mkdirs()
            File(dir, "Rus/Show - S01E01 - Title.mka").writeText("x")
            val probed = mutableListOf<String>()
            val meter = RecordingRenderer()

            inspectDirectory(dir, options(identify = true), meter) {
                probed += it.canonicalPath
                probedResult(it, track(0, "audio"))
            }

            probed shouldHaveSize probed.distinct().size
            // The meter's total is the real one: every probe happened under it.
            meter.progressTotal shouldBe probed.size
            meter.ticks shouldBe probed.size
        }

        test("a configured source that is also a discovered companion is read once") {
            // The canonical `${fileName}` layout produces exactly this every run. v1
            // reads such a file twice, because `File(path)` and `./path` have different absolute paths and
            // its cache is keyed on those; keying canonically is what collapses them to one subprocess.
            val dir = tempdir()
            File(dir, "Show - S01E01 - Title.mkv").writeText("x")
            File(dir, "Rus").mkdirs()
            File(dir, "Rus/Show - S01E01 - Title.mka").writeText("x")
            val probed = mutableListOf<String>()

            inspectDirectory(
                dir,
                options(identify = true, config = configWithSource("Rus/\${fileName}.mka")),
                SilentRenderer,
            ) { probed += it.canonicalPath; probedResult(it, track(0, "audio")) }

            probed shouldHaveSize 2
            probed shouldContainExactly probed.distinct()
        }
    }
})

private val identityA =
    VariantIdentity("A", leaf = "[GroupA]", suffix = null, dirRel = "Rus sound/[GroupA]", collision = false)

private fun externalFile(ext: String, language: String?, guessed: Boolean) = ExternalFile(
    relPath = "Rus sound/[GroupA]/01.$ext",
    extension = ext,
    tier = MatchTier.NAME,
    listing = ExternalListing.Tracks(
        listOf(
            ExternalTrack(
                0, typeClassOf(ext).label, CODEC_BY_EXTENSION.getValue(ext), language, guessed,
                default = false, forced = false, name = "",
            ),
        ),
    ),
)

private fun variantExternals(language: String, guessed: Boolean) =
    VariantExternals(identityA, listOf(externalFile("mka", language, guessed)))

private fun options(
    identify: Boolean = false,
    config: Config? = null,
    fileMasks: List<String> = emptyList(),
): InspectOptions = InspectOptions(
    identify = identify,
    check = !identify,
    config = config,
    substitution = SubstitutionEngine(),
    fileMasks = fileMasks,
)

private fun configWithSource(template: String): Config =
    Config(additionalSources = listOf(AdditionalSource(file = template)))

private fun track(
    id: Int,
    type: String,
    codec: String = "AAC",
    language: String? = "eng",
    name: String = "",
): ProbedTrack = ProbedTrack(id, type, codec, "A_$codec", language, name, default = false, forced = false)

private fun probedResult(file: File, vararg tracks: ProbedTrack): ProbeResult.Probed =
    ProbeResult.Probed(
        file = file,
        allTracks = tracks.toList(),
        tracks = tracks.associate { it.id to TrackSlot(it.id, signatureOf(it), null) },
        chapters = 0,
    )

private fun failed(file: File): ProbeResult = ProbeResult.Failed(file, "unused")
