package org.plukh.mkvtool.e2e.support

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The MKV derivation helpers, checked against what mkvmerge actually wrote.
 *
 * The failure this spec exists to catch is a helper that builds a plausible command and produces the wrong
 * file - which every case resting on it would then inherit without any of them naming it. A spec asserting
 * on the argument list would pass on exactly that, so every case here **probes the result through the
 * production parser** and asserts on `ProbedTrack` fields. The Groovy originals had no tests of their own
 * at all: they were verified indirectly by the cases that used them, which was tolerable while they were
 * frozen and is not for a fresh implementation the remaining translations will rest on.
 *
 * The source layout every id below indexes into, confirmed against the fixture rather than assumed:
 *
 * | id | type | language | name |
 * |---|---|---|---|
 * | 0 | video | und | Video |
 * | 1 | audio | jpn | Audio A |
 * | 2 | audio | eng | Audio B |
 * | 3 | audio | rus | Audio C |
 * | 4 | subtitles | eng | Subtitle A |
 * | 5 | subtitles | rus | Subtitle B (forced) |
 * | 6 | subtitles | jpn | Subtitle C |
 */
class MediaFixturesTest : FunSpec({

    context("buildVariant selects tracks") {
        test("a named subset survives and the rest is dropped").config(enabledOrReasonIf = needsMkvmerge) {
            val workDir = tempdir()
            val tracks = probe(buildVariant(File(workDir, "v.mkv"), audio = listOf(1, 3), subs = listOf(4)))
                .allTracks

            // Video is never dropped: buildVariant has no switch for it, because every fixture is a
            // stand-in for an episode.
            tracks.count { it.type == "video" } shouldBe 1
            tracks.filter { it.type == "audio" }.map { it.language } shouldContainExactly listOf("jpn", "rus")
            tracks.filter { it.type == "subtitles" }.map { it.language } shouldContainExactly listOf("eng")
        }

        test("an omitted type is dropped entirely").config(enabledOrReasonIf = needsMkvmerge) {
            val workDir = tempdir()
            val tracks = probe(buildVariant(File(workDir, "v.mkv"), audio = listOf(2))).allTracks

            tracks.count { it.type == "subtitles" } shouldBe 0
            tracks.count { it.type == "audio" } shouldBe 1
        }

        test("an empty list is refused rather than selecting nothing") {
            val workDir = tempdir()

            // Groovy emitted an empty --audio-tracks here and produced a file nobody meant. Refusing is
            // the point: the caller wrote emptyList() meaning one of two things, and neither is this.
            shouldThrow<IllegalArgumentException> {
                buildVariant(File(workDir, "v.mkv"), audio = emptyList())
            }
            shouldThrow<IllegalArgumentException> {
                buildVariant(File(workDir, "v.mkv"), subs = emptyList())
            }
        }
    }

    // The trap the whole file is shaped around: a variant is *configured* in source ids and *reported* in
    // output ids, and the two are only the same when every track is kept. Pinned in three parts, because
    // getting it backwards leaves a name silently unchanged - the fixture builds, the case runs, and it
    // asserts nothing.
    context("a variant has two id spaces") {
        test("what it reports are positions, not source ids").config(enabledOrReasonIf = needsMkvmerge) {
            val workDir = tempdir()
            val tracks = probe(buildVariant(File(workDir, "v.mkv"), audio = listOf(2, 3))).allTracks

            tracks.map { it.id } shouldContainExactly listOf(0, 1, 2)
            // Source 2 (eng) landed at 1 and source 3 (rus) at 2 - neither kept its own id.
            tracks.single { it.id == 1 }.language shouldBe "eng"
            tracks.single { it.id == 2 }.language shouldBe "rus"
        }

        test("but an override addresses the source id").config(enabledOrReasonIf = needsMkvmerge) {
            val workDir = tempdir()
            val variant = buildVariant(
                File(workDir, "v.mkv"),
                audio = listOf(2, 3),
                names = mapOf(2 to "Renamed"),
                langs = mapOf(3 to "ger"),
            )
            val tracks = probe(variant).allTracks

            // `--track-name` is a source-file option, so 2 means source 2 - the eng track, which the
            // result reports at id 1. Measured against real mkvmerge rather than reasoned about: the
            // Groovy suite documented this the other way round for the whole of the port.
            tracks.single { it.id == 1 }.language shouldBe "eng"
            tracks.single { it.id == 1 }.trackName shouldBe "Renamed"
            tracks.single { it.id == 2 }.language shouldBe "ger"
        }

        test("an override naming a track that was not kept is silently ignored")
            .config(enabledOrReasonIf = needsMkvmerge) {
                val workDir = tempdir()
                val variant = buildVariant(
                    File(workDir, "v.mkv"),
                    audio = listOf(2, 3),
                    names = mapOf(1 to "Renamed"),
                )

                // Source 1 (jpn) is not in this variant, so the override lands nowhere and mkvmerge says
                // nothing about it. This is the failure mode itself, pinned so it stays a known one: a
                // fixture written with output ids in mind produces exactly this and looks fine.
                probe(variant).allTracks.none { it.trackName == "Renamed" } shouldBe true
            }
    }

    context("buildVariant overrides what the surviving tracks carry") {
        test("names, languages and default flags land on the ids they name")
            .config(enabledOrReasonIf = needsMkvmerge) {
                val workDir = tempdir()
                val variant = fullCopy(
                    File(workDir, "v.mkv"),
                    names = mapOf(2 to "Other Studio"),
                    langs = mapOf(3 to "ger"),
                    defaults = mapOf(1 to false, 2 to true),
                )
                val tracks = probe(variant).allTracks.associateBy { it.id }

                tracks.getValue(2).trackName shouldBe "Other Studio"
                tracks.getValue(3).language shouldBe "ger"
                tracks.getValue(1).default shouldBe false
                tracks.getValue(2).default shouldBe true
                // Untouched ids keep what the source carried.
                tracks.getValue(1).trackName shouldBe "Audio A"
                tracks.getValue(2).language shouldBe "eng"
            }

        test("an external chapter file is muxed in").config(enabledOrReasonIf = needsMkvmerge) {
            val workDir = tempdir()
            val variant = fullCopy(File(workDir, "v.mkv"), chaptersFile = writeChapters(workDir))

            probe(variant).chapters shouldBe 2
            // The source carries none, which is what makes the two above attributable to the file.
            probe(fullCopy(File(workDir, "plain.mkv"))).chapters shouldBe 0
        }
    }

    test("fullCopy keeps every track, so output ids are the source ids")
        .config(enabledOrReasonIf = needsMkvmerge) {
            val workDir = tempdir()
            val tracks = probe(fullCopy(File(workDir, "v.mkv"))).allTracks

            tracks.map { it.id } shouldContainExactly (0..6).toList()
            // The whole reason this helper exists: id N in the copy is id N in the source, so a per-id
            // override in a check-report fixture provably hits the track it names.
            tracks.map { it.language } shouldContainExactly
                listOf("und", "jpn", "eng", "rus", "eng", "rus", "jpn")
            tracks.map { it.type } shouldContainExactly
                listOf("video", "audio", "audio", "audio", "subtitles", "subtitles", "subtitles")
        }

    context("extractTrack produces a single-track companion") {
        test("an audio track becomes a videoless file carrying its own language")
            .config(enabledOrReasonIf = needsMkvmerge) {
                val workDir = tempdir()
                val tracks = probe(
                    extractTrack(testMkv, File(workDir, "dub/e01.mka"), TrackType.AUDIO, 3),
                ).allTracks

                tracks.size shouldBe 1
                tracks.single().type shouldBe "audio"
                tracks.single().language shouldBe "rus"
                tracks.single().trackName shouldBe "Audio C"
            }

        test("a subtitle track does the same").config(enabledOrReasonIf = needsMkvmerge) {
            val workDir = tempdir()
            val tracks = probe(extractTrack(testMkv, File(workDir, "e01.mks"), TrackType.SUBTITLES, 5))
                .allTracks

            tracks.size shouldBe 1
            tracks.single().type shouldBe "subtitles"
            tracks.single().language shouldBe "rus"
            tracks.single().forced shouldBe true
        }

        test("a language override replaces the tag the source carried")
            .config(enabledOrReasonIf = needsMkvmerge) {
                val workDir = tempdir()
                // 'und' is the case that matters: Matroska's spelling of "untagged", which is what makes
                // discovery fall back on the folder-name guess. The selector addresses the source id, so
                // this has to be applied before any renumbering.
                val extracted = extractTrack(testMkv, File(workDir, "e01.mka"), TrackType.AUDIO, 2, "und")

                probe(extracted).allTracks.single().language shouldBe "und"
            }
    }

    test("writeChapters writes a chapter file mkvmerge reads") {
        val workDir = tempdir()
        val text = writeChapters(workDir).readText(Charsets.UTF_8)

        // OGM-simple, which mkvmerge reads as text - so the chapter cases need no binary fixture. That it
        // parses at all is asserted above, where the resulting variant reports two chapters.
        text.lines().filter { it.startsWith("CHAPTER") && !it.contains("NAME") } shouldContainExactly
            listOf("CHAPTER01=00:00:00.000", "CHAPTER02=00:00:02.000")
    }
})
