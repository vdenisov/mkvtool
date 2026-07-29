package org.plukh.mkvtool.e2e.support

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.plukh.mkvtool.core.MappingLoad
import org.plukh.mkvtool.core.loadConfig
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The directory-shaped fixtures.
 *
 * What these guard is different from what [MediaFixturesTest] guards. A media helper can produce a file
 * with the wrong tracks in it; a tree helper produces the right files in the wrong *shape* - one directory
 * short, one episode's companion missing, a language tagged where it was meant to be untagged - and every
 * discovery case built on it then asserts something other than what it says it does. So the inventory is
 * pinned as a whole rather than sampled, and the two facts a discovery case actually turns on (a real tag
 * on one group, no tag at all on the other) are pinned separately.
 */
class TreeFixturesTest : FunSpec({

    /** Every file under [dir], as forward-slashed paths relative to it, sorted. */
    fun inventory(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(dir).path.replace('\\', '/') }
            .sorted().toList()

    context("stageExternalText") {
        test("creates intermediate directories and writes UTF-8") {
            val workDir = tempdir()
            val file = stageExternalText(workDir, "Rus subs/[Group]/e01.ass", "Привет\n")

            file.exists() shouldBe true
            file.readText(StandardCharsets.UTF_8) shouldBe "Привет\n"
        }

        test("the default body is an SRT cue whatever extension is asked for") {
            val workDir = tempdir()

            // Deliberate, and half of what the discovery cases assert: nothing probes these formats, so a
            // subtitle file's language and grouping come from its name and its directory, never from
            // reading it. An .ass holding SRT is therefore a perfectly good fixture.
            stageExternalText(workDir, "e01.ass").readText(StandardCharsets.UTF_8) shouldContain "00:00:01,000"
        }
    }

    test("stageExternalTrack extracts into a nested path")
        .config(enabledOrReasonIf = needsMkvmerge) {
            val workDir = tempdir()
            val file = stageExternalTrack(workDir, "Rus sound/[GroupA]/e01.mka", TrackType.AUDIO, 3)

            file.relativeTo(workDir).path.replace('\\', '/') shouldBe "Rus sound/[GroupA]/e01.mka"
            probe(file).allTracks.single().language shouldBe "rus"
        }

    context("stageTree") {
        test("stages the whole release layout").config(enabledOrReasonIf = needsMkvmerge) {
            val workDir = tempdir()
            stageTree(workDir)

            // Asserted as a whole rather than sampled: a missing file here does not fail a discovery
            // case, it quietly narrows what that case covers.
            inventory(workDir) shouldContainExactly listOf(
                "Rus sound/[GroupA]/Show - S01E01 - Title.mka",
                "Rus sound/[GroupA]/Show - S01E02 - Title.mka",
                "Rus sound/[GroupA]/Show - S01E03 - Title.mka",
                "Rus sound/[GroupB]/Show - S01E01 - Title.mka",
                "Rus subs/[GroupA]/Bonus.ass",
                "Rus subs/[GroupA]/Show - S01E01 - Title.ass",
                "Rus subs/[GroupA]/Show - S01E02 - Title.ass",
                "Show - S01E01 - Title.mkv",
                "Show - S01E01 - Title.rus.srt",
                "Show - S01E02 - Title.mkv",
                "Show - S01E03 - Title.mkv",
                "extras/Sample.mkv",
            )
        }

        test("one dub group is tagged and the other is not")
            .config(enabledOrReasonIf = needsMkvmerge) {
                val workDir = tempdir()
                stageTree(workDir)

                // The pair the discovery cases turn on. GroupA carries a real tag, so probed-field-wins
                // has something to win with; GroupB is 'und' - Matroska's "untagged" - so its language can
                // only come from the "Rus sound" directory name, which is what the guesser is for. Swap
                // either and the cases still pass while testing the other thing.
                probe(File(workDir, "Rus sound/[GroupA]/Show - S01E01 - Title.mka"))
                    .allTracks.single().language shouldBe "rus"
                probe(File(workDir, "Rus sound/[GroupB]/Show - S01E01 - Title.mka"))
                    .allTracks.single().language shouldBe "und"
            }

        test("GroupA's subtitle coverage is deliberately incomplete")
            .config(enabledOrReasonIf = needsMkvmerge) {
                val workDir = tempdir()
                stageTree(workDir)

                // Two subtitles against three episodes: a partially released dub, which is the ordinary
                // situation the pre-flight and the check grouping exist for.
                File(workDir, "Rus subs/[GroupA]").listFiles().orEmpty()
                    .count { it.name.startsWith("Show - ") } shouldBe 2
                File(workDir, "Rus sound/[GroupA]").listFiles().orEmpty().size shouldBe 3
            }
    }

    context("stageBatch") {
        test("stages three episodes, a sample and a non-media file")
            .config(enabledOrReasonIf = needsMkvmerge) {
                val workDir = tempdir()
                stageBatch(workDir)

                inventory(workDir) shouldContainExactly listOf(
                    "Show.S01E01.mkv",
                    "Show.S01E01.sample.mkv",
                    "Show.S01E02.mkv",
                    "Show.S01E03.mkv",
                    "config.yaml",
                    "notes.txt",
                )
            }

        test("the config it writes loads, and leaves trackOrder to be derived")
            .config(enabledOrReasonIf = needsMkvmerge) {
                val workDir = tempdir()
                stageBatch(workDir)

                val loaded = loadConfig(File(workDir, "config.yaml"))
                check(loaded is MappingLoad.Loaded) { "stageBatch wrote a config the loader rejected: $loaded" }

                loaded.value.mainSource.audioTracks.single().id shouldBe 1
                // Omitted on purpose, so every case building on this keeps exercising derivation.
                loaded.value.trackOrder shouldBe null
            }
    }
})
