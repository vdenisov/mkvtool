package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.plukh.mkvtool.core.ProbedTrack
import org.plukh.mkvtool.core.TrackSelection
import java.io.File

/**
 * The sample reports in `docs/reference.md` are what the tool actually prints.
 *
 * They were captured from real runs, which is the only way to get them right — and exactly why they go
 * stale silently: the rendering has changed twice since they were written (groups gained episode ranges and
 * started naming their largest member), and the document went on describing the older shape for two
 * releases. Pinning them here means a formatting change fails the build naming the block to re-capture,
 * rather than leaving the reader to discover the drift.
 *
 * This is a *unit* test on purpose. The report renders from probe records, so the scenarios are built as
 * data and nothing here needs mkvmerge or a subprocess, which keeps the tier's contract intact. What that
 * costs is one thing worth knowing: the fixtures reproduce the *shape* of the real batches the examples came
 * from, so if a block is re-captured from a genuinely different batch, the fixture below has to move with it.
 */
class ReferenceDocExamplesTest : FunSpec({

    val episodes = listOf(
        "Twin Peaks - S01E01 - Northwest Passage.mkv",
        "Twin Peaks - S01E02 - Traces to Nowhere.mkv",
        "Twin Peaks - S01E03 - Zen, or the Skill to Catch a Killer.mkv",
        "Twin Peaks - S01E04 - Rest in Pain.mkv",
        "Twin Peaks - S01E05 - The One-Armed Man.mkv",
    )

    fun video(name: String = "Video"): ProbedTrack =
        t(0, "video", "AVC/H.264/MPEG-4p10", "und", name = name)

    test("the layout-grouping example is what the renderer prints") {
        // Three episodes in video/audio/subs order and two that lead with subtitles — the mixed-layout
        // season the two-layer grouping exists for.
        val standard = episodes.take(3).map {
            probed(
                it,
                video(),
                t(1, "audio", "AAC", "jpn", name = "Audio A", default = true),
                t(2, "subtitles", "SubRip/SRT", "eng", name = "Subtitle A", default = true),
            )
        }
        val shifted = episodes.drop(3).map {
            probed(
                it,
                t(0, "subtitles", "SubRip/SRT", "eng", name = "Subtitle A", default = true),
                video().copy(id = 1),
                t(2, "audio", "AAC", "jpn", name = "Audio A", default = true),
            )
        }
        val selection = TrackSelection(
            hasConfig = true,
            videoIds = setOf(0),
            audioIds = setOf(1),
            subtitleIds = setOf(2),
        )

        render(standard + shifted, selection = selection) shouldContainBlock
            docBlock("*** Layout 1 (3 files - episodes 01-03)")
    }

    test("the value-split fragment is what the renderer prints") {
        // One episode whose second audio track carries another studio's name, against two that agree.
        fun tracks(secondAudioName: String) = arrayOf(
            video(),
            t(1, "audio", "AAC", "jpn", name = "Audio A", default = true),
            t(2, "audio", "AAC", "eng", name = secondAudioName),
            t(3, "audio", "AAC", "rus", name = "Audio C"),
            t(4, "subtitles", "SubRip/SRT", "eng", name = "Subtitle A", default = true),
            t(5, "subtitles", "SubRip/SRT", "rus", name = "Subtitle B", default = true, forced = true),
            t(6, "subtitles", "SubRip/SRT", "jpn", name = "Subtitle C", default = true),
        )
        val infos = listOf(
            probed(episodes[0], *tracks("Audio B")),
            probed(episodes[1], *tracks("Audio B")),
            probed(episodes[2], *tracks("Other Studio")),
        )
        val selection = TrackSelection(
            hasConfig = true,
            videoIds = setOf(0),
            audioIds = setOf(2),
            titleById = mapOf(2 to "Russian"),
        )

        render(infos, selection = selection) shouldContainBlock docBlock("<- Twin Peaks - S01E03")
    }

    test("the informational summary is what the renderer prints") {
        // A flag difference on a track the config selects, where every subtitle track present is copied
        // anyway — so ids cannot select the wrong thing however they shift, and the finding is a note
        // rather than a blocker.
        fun tracks(default: Boolean) = arrayOf(
            video(),
            t(1, "audio", "AAC", "eng", name = "English", default = true),
            t(2, "subtitles", "SubRip/SRT", "eng", name = "Signs", default = default),
        )
        val infos = listOf(
            probed(episodes[0], *tracks(default = true)),
            probed(episodes[1], *tracks(default = false)),
        )
        val selection = TrackSelection(
            hasConfig = true,
            videoIds = setOf(0),
            audioIds = setOf(1),
            subtitleIds = setOf(2),
            titleById = mapOf(2 to "Signs"),
        )

        render(infos, selection = selection) shouldContainBlock
            docBlock("*** 1 informational")
    }
})

/**
 * The fenced block in `docs/reference.md` holding [marker], with the fence's own indentation removed — a
 * block inside a list item is indented in the source and not in the output it quotes.
 */
private fun docBlock(marker: String): String {
    val file = File("docs/reference.md")
    check(file.isFile) { "docs/reference.md not found — this test reads the repository, so it runs from the project directory" }

    val blocks = mutableListOf<String>()
    var fenceIndent = -1
    var current = mutableListOf<String>()
    file.readLines(Charsets.UTF_8).forEach { line ->
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") && fenceIndent < 0 -> {
                fenceIndent = line.length - trimmed.length
                current = mutableListOf()
            }
            trimmed.startsWith("```") -> {
                blocks += current.joinToString("\n")
                fenceIndent = -1
            }
            fenceIndent >= 0 -> current += line.drop(fenceIndent)
        }
    }

    val matches = blocks.filter { it.contains(marker) }
    check(matches.size == 1) { "expected exactly one fenced block containing \"$marker\", found ${matches.size}" }
    return matches.single()
}

/**
 * Trailing whitespace is stripped from both sides before comparing. The renderer pads its last column, and
 * no editor or reviewer would keep those spaces alive in a markdown file — so requiring them would fail for
 * a reason that has nothing to do with what the reader sees.
 */
private infix fun String.shouldContainBlock(block: String) {
    fun normalize(text: String) = text.lines().joinToString("\n") { it.trimEnd() }.trim()

    val rendered = normalize(this)
    val expected = normalize(block)
    if (!rendered.contains(expected)) {
        // shouldContain gives the readable diff; the check above is what decides, so the two agree.
        rendered shouldContain expected
        // Unreachable unless the matcher disagrees with `contains`, which would itself be worth knowing.
        rendered shouldBe expected
    }
}
