package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * `trackOrder`: derived from the configured tracks when the config names none, and checked against them
 * when it does.
 *
 * The checking is warnings only, and that is the interesting part. mkvmerge silently discards a
 * `--track-order` entry matching no muxed track — it exits 0 and produces a perfectly valid file in the
 * wrong order — so a stale order fails *quietly*, which is the only reason any of this exists. Failing
 * loudly instead is not an option either: a config that works today has to keep working.
 */
class TrackOrderTest : FunSpec({

    context("derivation") {
        data class Case(val name: String, val config: Config, val expected: String)

        withData(
            nameFn = { "${it.name} -> ${it.expected}" },
            Case("video only", config(), "0:0"),
            Case(
                "audio and subtitles follow the video, in listed order",
                config(audio = listOf(2, 1), subtitles = listOf(4)),
                "0:0,0:2,0:1,0:4",
            ),
            Case(
                // One entry per source, not per track: a companion is a single-track file to mkvmerge, so
                // the source index is what tells two of them apart.
                "each additional source contributes one entry, whatever it holds",
                config(audio = listOf(1), sources = 2),
                "0:0,0:1,1:0,2:0",
            ),
        ) { deriveTrackOrder(it.config) shouldBe it.expected }
    }

    test("an omitted trackOrder derives, and has nothing to warn about") {
        val order = resolveTrackOrder(config(audio = listOf(1, 2), subtitles = listOf(4)))

        order.shouldBeInstanceOf<TrackOrder.Derived>()
        order.order shouldBe "0:0,0:1,0:2,0:4"
    }

    context("validation of a configured order") {
        test("an order matching the configured tracks reports nothing") {
            val order = resolve("0:0,0:1,0:4", audio = listOf(1), subtitles = listOf(4))

            order.order shouldBe "0:0,0:1,0:4"
            order.malformed.shouldBeEmpty()
            order.unknown.shouldBeEmpty()
            order.missing.shouldBeEmpty()
        }

        test("an id nothing configures is unknown — mkvmerge would ignore it in silence") {
            resolve("0:0,0:2,0:999", audio = listOf(2)).unknown shouldContainExactly listOf("0:999")
        }

        test("a configured id the order leaves out is missing, and is still muxed") {
            resolve("0:0,0:1", audio = listOf(1, 2)).missing shouldContainExactly listOf("0:2")
        }

        test("anything that is not sourceIndex:trackId is malformed, and is not also 'unknown'") {
            val order = resolve("0:0,bogus,0:1:2", audio = listOf(1))

            order.malformed shouldContainExactly listOf("bogus", "0:1:2")
            // The malformed entries are excluded from the unknown check; only the omitted 0:1 is reported
            // beyond them, so one mistake produces one diagnosis rather than two.
            order.unknown.shouldBeEmpty()
            order.missing shouldContainExactly listOf("0:1")
        }

        test("whitespace around an entry is trimmed, and an empty entry is not an entry") {
            val order = resolve(" 0:0 , 0:1 ,", audio = listOf(1))

            order.malformed.shouldBeEmpty()
            order.missing.shouldBeEmpty()
        }

        test("the order is used exactly as written, warnings or not") {
            // Never rewritten, never repaired: what the config says is what mkvmerge is handed.
            resolve("0:9,junk", audio = listOf(1)).order shouldBe "0:9,junk"
        }

        test("two configured tracks sharing an id are one thing the order can omit") {
            // v1 compared against a Set, so a duplicated id was reported once. Reproduced, and it is the
            // reason `missing` is de-duplicated where `unknown` is not.
            resolve("0:0", audio = listOf(1, 1)).missing shouldContainExactly listOf("0:1")
        }
    }
})

private fun config(
    audio: List<Int> = emptyList(),
    subtitles: List<Int> = emptyList(),
    sources: Int = 0,
    trackOrder: String? = null,
) = Config(
    general = GeneralConfig(destinationDir = "mkv"),
    mainSource = MainSourceConfig(
        videoTrack = VideoTrackConfig(language = "ja"),
        audioTracks = audio.map { TrackConfig(id = it) },
        subtitleTracks = subtitles.map { TrackConfig(id = it) },
    ),
    additionalSources = (1..sources).map { AdditionalSource(file = "s$it.mka") },
    trackOrder = trackOrder,
)

private fun resolve(order: String, audio: List<Int> = emptyList(), subtitles: List<Int> = emptyList()) =
    resolveTrackOrder(config(audio, subtitles, trackOrder = order)) as TrackOrder.Configured
