package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * Parsing `mkvmerge -J` into typed records, and the grouping the consistency check is built on. No
 * mkvmerge in this tier: every document is a fixture string, which is what lets the failure cases — an
 * unrecognised container, a truncated document — be tested at all.
 */
class ProbeTest : FunSpec({

    context("parseProbe container guards") {
        data class Case(val label: String, val json: String, val reason: String)

        // mkvmerge exits 0 on a file it cannot read and says so in `container` instead, so these are the
        // failures that an exit-code check alone would let through — as a file with zero tracks, which
        // reads as "every track is absent here" and poisons the whole report.
        withData(
            nameFn = { it.label },
            Case(
                "an unrecognised container",
                """{"container":{"recognized":false,"supported":false},"tracks":[]}""",
                "not recognised as a media file",
            ),
            Case(
                "a recognised but unsupported container",
                """{"container":{"recognized":true,"supported":false},"tracks":[]}""",
                "container not supported by mkvmerge",
            ),
            Case(
                "a missing container object defaults to unrecognised",
                """{"tracks":[]}""",
                "not recognised as a media file",
            ),
            Case(
                "a truncated document is a failure, not an exception",
                """{"container":{"recognized":true,""",
                "mkvmerge output could not be parsed (JsonDecodingException)",
            ),
            Case("empty output", "", "mkvmerge output could not be parsed (JsonDecodingException)"),
        ) {
            val result = parseProbe(File("x.mkv"), it.json)
            result.shouldBeInstanceOf<ProbeResult.Failed>().reason shouldBe it.reason
        }
    }

    context("parseProbe track mapping") {
        test("maps every field any consumer reads, with nothing defaulted") {
            val result = parseProbe(File("ep.mkv"), fixture()).shouldBeInstanceOf<ProbeResult.Probed>()

            result.allTracks shouldHaveSize 3
            val video = result.allTracks[0]
            video.id shouldBe 0
            video.type shouldBe "video"
            video.codec shouldBe "AVC/H.264/MPEG-4p10"
            video.codecId shouldBe "V_MPEG4/ISO/AVC"
            video.language shouldBe "und"
            video.trackName shouldBe "Episode Title"
            video.default shouldBe true
            video.forced shouldBe false
        }

        test("absent properties stay absent rather than being invented") {
            // A raw .ass probes with no codec_id, no language and no track name at all.
            val json = """{"container":{"recognized":true,"supported":true},
                          "tracks":[{"id":0,"type":"subtitles","codec":"SubStationAlpha"}]}"""

            val result = parseProbe(File("x.ass"), json).shouldBeInstanceOf<ProbeResult.Probed>()

            val track = result.allTracks.single()
            track.codecId shouldBe null
            track.language shouldBe null
            track.trackName shouldBe null
            track.default shouldBe false
            track.forced shouldBe false
        }

        test("unknown keys are ignored, so a newer mkvmerge does not break the parse") {
            val json = """{"container":{"recognized":true,"supported":true,"properties":{"duration":42}},
                          "tracks":[{"id":0,"type":"audio","codec":"AC-3","brand_new_key":[1,2],
                                     "properties":{"language":"eng","audio_channels":6}}],
                          "some_future_section":{"a":1}}"""

            val result = parseProbe(File("x.mkv"), json).shouldBeInstanceOf<ProbeResult.Probed>()

            result.allTracks.single().language shouldBe "eng"
        }

        test("chapter entries are summed across every edition") {
            val json = """{"container":{"recognized":true,"supported":true},"tracks":[],
                          "chapters":[{"num_entries":5},{"num_entries":3}]}"""

            parseProbe(File("x.mkv"), json)
                .shouldBeInstanceOf<ProbeResult.Probed>().chapters shouldBe 8
        }

        test("no chapters section means no chapters") {
            parseProbe(File("x.mkv"), fixture())
                .shouldBeInstanceOf<ProbeResult.Probed>().chapters shouldBe 0
        }
    }

    context("signatureOf") {
        test("fills mkvmerge's absences: und for language, ? for type and codec, empty for a name") {
            val signature = signatureOf(ProbedTrack(1, null, null, null, null, null, false, false))

            signature.type shouldBe "?"
            signature.codec shouldBe "?"
            signature.language shouldBe "und"
            signature.name shouldBe ""
        }

        test("a video track's name is nulled so it can never enter a group key") {
            // It routinely carries the episode title and so differs by design; the real value rides on
            // TrackSlot.videoName instead, which no comparison reads.
            val result = parseProbe(File("ep.mkv"), fixture()).shouldBeInstanceOf<ProbeResult.Probed>()

            result.tracks.getValue(0).signature.name shouldBe null
            result.tracks.getValue(0).videoName shouldBe "Episode Title"
            // A non-video track keeps its name, and carries no videoName.
            result.tracks.getValue(1).signature.name shouldBe "Japanese"
            result.tracks.getValue(1).videoName shouldBe null
        }

        test("two tracks differing only in an ignored field compare equal") {
            // Signature equality *is* the group key, so anything not in it cannot split a group.
            val base = ProbedTrack(1, "audio", "AC-3", "A_AC3", "eng", "Commentary", true, false)

            signatureOf(base) shouldBe signatureOf(base.copy(id = 7, codecId = "SOMETHING_ELSE"))
        }
    }

    context("groupTracks") {
        test("ranks by population and never anchors on the first file") {
            // If a value changed from episode 3 onward, anchoring on file one would report the majority
            // as deviant against a sample of one. Which group is correct is the user's call.
            val infos = listOf(
                probed("e01.mkv", track(1, "audio", language = "jpn")),
                probed("e02.mkv", track(1, "audio", language = "rus")),
                probed("e03.mkv", track(1, "audio", language = "rus")),
            )

            val group = groupTracks(infos).single()

            group.id shouldBe 1
            group.consistent shouldBe false
            group.varying shouldContainExactly listOf(SignatureField.LANGUAGE)
            group.groups.map { it.fileNames } shouldContainExactly
                listOf(listOf("e02.mkv", "e03.mkv"), listOf("e01.mkv"))
            group.groups.map { it.minority } shouldContainExactly listOf(false, true)
        }

        test("an even split singles out nobody") {
            val infos = listOf(
                probed("e01.mkv", track(1, "audio", language = "jpn")),
                probed("e02.mkv", track(1, "audio", language = "rus")),
            )

            groupTracks(infos).single().groups.map { it.minority } shouldContainExactly listOf(false, false)
        }

        test("a tie in population breaks on the first file name, for a deterministic report") {
            val infos = listOf(
                probed("z.mkv", track(1, "audio", language = "jpn")),
                probed("a.mkv", track(1, "audio", language = "rus")),
            )

            groupTracks(infos).single().groups.map { it.fileNames[0] } shouldContainExactly
                listOf("a.mkv", "z.mkv")
        }

        test("a slot absent from some files becomes a null-signature group, not a dropped row") {
            val infos = listOf(
                probed("e01.mkv", track(1, "audio"), track(2, "subtitles")),
                probed("e02.mkv", track(1, "audio")),
            )

            val subtitles = groupTracks(infos).single { it.id == 2 }

            subtitles.missing shouldContainExactly listOf("e02.mkv")
            subtitles.groups.first { it.slot == null }.fileNames shouldContainExactly listOf("e02.mkv")
            // The type still reports from a file that has it, not from the hole.
            subtitles.type shouldBe "subtitles"
        }

        test("every differing field is named, in signature order") {
            val infos = listOf(
                probed("e01.mkv", track(1, "audio", codec = "AC-3", language = "eng", name = "A")),
                probed("e02.mkv", track(1, "audio", codec = "DTS", language = "rus", name = "A")),
            )

            groupTracks(infos).single().varying shouldContainExactly
                listOf(SignatureField.CODEC, SignatureField.LANGUAGE)
        }

        test("a uniform batch is consistent with a single group") {
            val infos = listOf(
                probed("e01.mkv", track(0, "video"), track(1, "audio")),
                probed("e02.mkv", track(0, "video"), track(1, "audio")),
            )

            groupTracks(infos).all { it.consistent } shouldBe true
            groupTracks(infos).map { it.id } shouldContainExactly listOf(0, 1)
        }

        test("a video track's differing title does not split a group") {
            val infos = listOf(
                probed("e01.mkv", track(0, "video", name = "First Episode")),
                probed("e02.mkv", track(0, "video", name = "Second Episode")),
            )

            val group = groupTracks(infos).single()

            group.consistent shouldBe true
            group.groups.single().slot!!.videoName shouldBe "First Episode"
        }
    }

    context("layoutKey") {
        test("is the type at each id, in id order") {
            val info = probed("e.mkv", track(2, "subtitles"), track(0, "video"), track(1, "audio"))

            internalLayoutKey(info) shouldBe "0:video 1:audio 2:subtitles"
        }

        test("two files with the same types at the same ids share a layout") {
            val a = probed("a.mkv", track(0, "video"), track(1, "audio", codec = "AC-3"))
            val b = probed("b.mkv", track(0, "video"), track(1, "audio", codec = "DTS"))

            internalLayoutKey(a) shouldBe internalLayoutKey(b)
        }

        test("a shifted track order is a different layout") {
            val a = probed("a.mkv", track(0, "video"), track(1, "audio"), track(2, "subtitles"))
            val b = probed("b.mkv", track(0, "video"), track(1, "subtitles"), track(2, "audio"))

            internalLayoutKey(a) shouldBe "0:video 1:audio 2:subtitles"
            internalLayoutKey(b) shouldBe "0:video 1:subtitles 2:audio"
        }

        test("external slots join the key as a sorted set, since they have no order") {
            val info = probed("e.mkv", track(0, "video"))
            val externals = mapOf(
                "B/audio/mka" to external("B/audio/mka", "B"),
                "A/subtitles/ass" to external("A/subtitles/ass", "A"),
            )

            layoutKey(info, externals) shouldBe "0:video + A/subtitles/ass B/audio/mka"
        }

        test("no externals leaves the key untouched, which is why mux is unaffected") {
            val info = probed("e.mkv", track(0, "video"))

            layoutKey(info) shouldBe "0:video"
            layoutKey(info, emptyMap()) shouldBe internalLayoutKey(info)
        }

        test("the same .mkv files with different dubs attached are different layouts") {
            // The group count answers "how many muxing passes", so a season whose dubs arrive at
            // different episodes is not one job however uniform its .mkv files are.
            val info = probed("e.mkv", track(0, "video"))

            layoutKey(info, mapOf("A/audio/mka" to external("A/audio/mka", "A"))) shouldNotBe
                layoutKey(info, emptyMap())
        }
    }

    context("groupExternals") {
        test("groups by attached slot key, and a guessed language does not split a group") {
            // `guessed` rides outside the signature for exactly this reason: it changes presentation,
            // never what groups with what.
            val a = probed("e01.mkv", track(0, "video"))
            val b = probed("e02.mkv", track(0, "video"))
            val externals = mapOf(
                a to mapOf("A/audio/mka" to external("A/audio/mka", "A", language = "rus", guessed = true)),
                b to mapOf("A/audio/mka" to external("A/audio/mka", "A", language = "rus", guessed = false)),
            )

            val group = groupExternals(listOf(a, b)) { externals.getValue(it) }.single()

            group.consistent shouldBe true
            group.id shouldBe "A/audio/mka"
        }

        test("a value that changes mid-season splits the slot") {
            val a = probed("e01.mkv", track(0, "video"))
            val b = probed("e02.mkv", track(0, "video"))
            val externals = mapOf(
                a to mapOf("A/audio/mka" to external("A/audio/mka", "A", language = "rus")),
                b to mapOf("A/audio/mka" to external("A/audio/mka", "A", language = "und")),
            )

            val group = groupExternals(listOf(a, b)) { externals.getValue(it) }.single()

            group.consistent shouldBe false
            group.varying shouldContainExactly listOf(SignatureField.LANGUAGE)
        }
    }

    context("findDuplicates") {
        test("flags only tracks alike in type, language, codec and name") {
            val info = probed(
                "e01.mkv",
                track(1, "audio", codec = "AC-3", language = "eng", name = ""),
                track(2, "audio", codec = "AC-3", language = "eng", name = ""),
            )

            val duplicate = findDuplicates(listOf(info)).single()

            duplicate.ids shouldContainExactly listOf(1, 2)
            duplicate.type shouldBe "audio"
            duplicate.language shouldBe "eng"
            duplicate.codec shouldBe "AC-3"
            duplicate.name shouldBe ""
            duplicate.fileNames shouldContainExactly listOf("e01.mkv")
        }

        data class Distinguishable(val label: String, val second: ProbedTrack)

        withData(
            nameFn = { it.label },
            // Two English tracks in different codecs are perfectly distinguishable...
            Distinguishable("a different codec", track(2, "audio", codec = "DTS", language = "eng")),
            // ...and so is one that says what it is.
            Distinguishable(
                "a different name",
                track(2, "audio", codec = "AC-3", language = "eng", name = "Director's Commentary"),
            ),
            Distinguishable("a different language", track(2, "audio", codec = "AC-3", language = "rus")),
            Distinguishable("a different type", track(2, "subtitles", codec = "AC-3", language = "eng")),
        ) {
            val info = probed("e01.mkv", track(1, "audio", codec = "AC-3", language = "eng"), it.second)

            findDuplicates(listOf(info)).shouldBeEmpty()
        }

        test("video tracks are never ambiguous, whatever they carry") {
            val info = probed("e01.mkv", track(0, "video"), track(1, "video"))

            findDuplicates(listOf(info)).shouldBeEmpty()
        }

        test("one ambiguity shared by a whole batch is reported once, listing its files") {
            val infos = (1..3).map {
                probed(
                    "e0$it.mkv",
                    track(1, "audio", codec = "AC-3", language = "eng"),
                    track(2, "audio", codec = "AC-3", language = "eng"),
                )
            }

            val duplicate = findDuplicates(infos).single()

            duplicate.fileNames shouldContainExactly listOf("e01.mkv", "e02.mkv", "e03.mkv")
        }

        test("a flag difference does not make two tracks distinguishable") {
            // default/forced are deliberately outside the ambiguity key: they do not help you pick.
            val info = probed(
                "e01.mkv",
                track(1, "audio", codec = "AC-3", language = "eng", default = true),
                track(2, "audio", codec = "AC-3", language = "eng", default = false),
            )

            findDuplicates(listOf(info)) shouldHaveSize 1
        }
    }
})

/** A three-track document with every field any consumer reads populated. */
private fun fixture(): String = """
    {
      "container": {"recognized": true, "supported": true},
      "tracks": [
        {"id": 0, "type": "video", "codec": "AVC/H.264/MPEG-4p10",
         "properties": {"codec_id": "V_MPEG4/ISO/AVC", "language": "und",
                        "track_name": "Episode Title", "default_track": true, "forced_track": false}},
        {"id": 1, "type": "audio", "codec": "AC-3",
         "properties": {"codec_id": "A_AC3", "language": "jpn",
                        "track_name": "Japanese", "default_track": true, "forced_track": false}},
        {"id": 2, "type": "subtitles", "codec": "SubRip/SRT",
         "properties": {"codec_id": "S_TEXT/UTF8", "language": "eng",
                        "track_name": "", "default_track": false, "forced_track": true}}
      ]
    }
""".trimIndent()

private fun track(
    id: Int,
    type: String,
    codec: String = "AC-3",
    language: String = "eng",
    name: String = "",
    default: Boolean = false,
    forced: Boolean = false,
): ProbedTrack = ProbedTrack(id, type, codec, "CODEC_ID", language, name, default, forced)

private fun probed(fileName: String, vararg tracks: ProbedTrack): ProbeResult.Probed =
    ProbeResult.Probed(
        file = File(fileName),
        allTracks = tracks.toList(),
        tracks = tracks.associate {
            it.id to TrackSlot(it.id, signatureOf(it), if (it.type == "video") (it.trackName ?: "") else null)
        },
        chapters = 0,
    )

private fun external(
    key: String,
    label: String,
    type: String = "audio",
    language: String = "rus",
    guessed: Boolean = false,
): ExternalSlot = ExternalSlot(
    key = key,
    signature = TrackSignature(type, "Matroska", language, "", default = false, forced = false),
    guessed = guessed,
    label = label,
    variantName = "[Group$label]",
)
