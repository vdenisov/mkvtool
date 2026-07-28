package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import org.plukh.mkvtool.out.pluralize

/**
 * The display-only half of the episodes library, transcribed value for value from the acceptance
 * harness's `122_batch_episode_labels` — which tested the v1 library in-process and retired with it —
 * plus the edges that case never reached.
 *
 * These labels are **batch-relative**: they need the other names to know where the number starts, which is
 * what makes them safe (`1080p` and `x264` sit inside the shared prefix) and exactly why they cannot answer
 * "which episode is this file" for one file on its own. Nothing that renames or resolves an episode number
 * may use them.
 */
class BatchLabelsTest : FunSpec({

    /** What a layout-group header shows, before a renderer turns it into words. */
    fun ranges(names: List<String>): String = formatRanges(batchLabels(names).values)

    fun padded(n: Int): String = n.toString().padStart(2, '0')

    context("batchLabels") {
        data class Case(
            val label: String,
            val names: List<String>,
            val expected: String,
        )

        withData(
            nameFn = { it.label },
            Case(
                "an SxxEyy batch uses the episode number",
                (1..4).map { "My Show - S01E0$it - Title" },
                "01-04",
            ),
            Case(
                "a plain numbered batch is anchored on its common prefix",
                (1..10).map { "[Salender-Raws] Hellsing OVA - ${padded(it)} (BD 1920x1080 x264 5.1 FLAC)" },
                "01-10",
            ),
            // The prefix of 10..19 ends mid-number, so the trailing digits have to come off it or the
            // batch reads as 0-9.
            Case("trailing digits are trimmed off the prefix", (10..19).map { "Show - $it" }, "10-19"),
            Case("padding survives the trim", (1..9).map { "Show - 0$it" }, "01-09"),
            Case("no separator is still a number", (1..3).map { "Hellsing$it" }, "1-3"),
            Case(
                "a number in the show name is inside the prefix, so it cannot be mistaken for one",
                (1..3).map { "Show (2024) 0$it" },
                "01-03",
            ),
            Case("gaps break the run", listOf("Show - 01", "Show - 02", "Show - 05"), "01-02, 05"),
        ) { ranges(it.names) shouldBe it.expected }

        test("an unnumbered batch has no labels at all") {
            batchLabels(listOf("Alpha", "Beta")) shouldBe emptyMap()
        }

        // All-or-nothing: a partly-labelled group is worse than an unlabelled one, so one name whose
        // remainder does not start with digits declines for the whole batch.
        test("one unnumbered name and the whole idea is off") {
            batchLabels(listOf("Show - S01E01 - T", "Show - S01E02 - T", "Show - Special - T")) shouldBe emptyMap()
        }

        test("an empty batch declines") {
            batchLabels(emptyList()) shouldBe emptyMap()
        }

        test("a single name is its own batch") {
            batchLabels(listOf("Show 05")) shouldBe mapOf("Show 05" to "05")
        }

        // The label is capped at four digits, which is what keeps a stray long number from becoming one.
        test("a label takes at most four digits") {
            batchLabels(listOf("Show 12345")) shouldBe mapOf("Show 12345" to "1234")
        }

        test("labels are keyed by name, so a repeated name collapses") {
            batchLabels(listOf("Show - 01", "Show - 01")) shouldBe mapOf("Show - 01" to "01")
        }
    }

    context("formatRanges") {
        data class Case(
            val label: String,
            val labels: List<String>,
            val expected: String,
        )

        withData(
            nameFn = { it.label },
            Case("nothing to show", emptyList(), ""),
            Case("a single label renders bare, not as a run of one", listOf("01"), "01"),
            Case("a contiguous run collapses", listOf("01", "02", "03"), "01-03"),
            Case("runs and singletons mix", listOf("01", "03", "04", "07"), "01, 03-04, 07"),
            Case("input is sorted first", listOf("03", "01", "02"), "01-03"),
            Case("duplicates collapse", listOf("01", "01", "02"), "01-02"),
            // Anything not a plain number degrades to a comma-joined list rather than lying about a range.
            Case("one non-numeric label drops the whole idea of runs", listOf("01", "02", "extra"), "01, 02, extra"),
            Case("all non-numeric", listOf("beta", "alpha"), "alpha, beta"),
            // Sorting is lexicographic while runs are detected numerically, so mixed widths come out odd.
            // Unreachable through batchLabels, whose labels all come from one batch and share a width.
            Case("mixed-width labels sort as strings", listOf("9", "10"), "10, 9"),
        ) { formatRanges(it.labels) shouldBe it.expected }
    }

    context("membershipLabels") {
        test("the distinct labels of the group") {
            membershipLabels((1..3).map { "Show - S01E0$it - T" }) shouldBe listOf("01", "02", "03")
        }

        test("a single-episode group") {
            membershipLabels(listOf("Show - S01E01 - T")) shouldBe listOf("01")
        }

        test("two files for one episode are one label") {
            membershipLabels(listOf("Show - S01E01 - A", "Show - S01E01 - B")) shouldBe listOf("01")
        }

        test("declines when the batch is not numbered, so the caller falls back to file names") {
            membershipLabels(listOf("Alpha", "Beta")) shouldBe null
        }

        // Each layout group is its own batch: the numbers a group shows are anchored on the names it holds,
        // which is what lets one report print "01-02" against one group and "03-04" against the next.
        test("labels are group-local") {
            membershipLabels(listOf("Show - S01E01 - T", "Show - S01E02 - T")) shouldBe listOf("01", "02")
            membershipLabels(listOf("Show - S01E03 - T", "Show - S01E04 - T")) shouldBe listOf("03", "04")
        }

        // The phrase itself belongs to the renderer (task 2.6); this pins that the data is enough to build
        // it, and keeps the harness's own expected strings under test in the meantime.
        test("carries everything a renderer needs for the membership line") {
            fun line(names: List<String>): String? =
                membershipLabels(names)?.let { "${pluralize(it.size, "episode")} ${formatRanges(it)}" }

            line((1..3).map { "Show - S01E0$it - T" }) shouldBe "episodes 01-03"
            line(listOf("Show - S01E01 - T")) shouldBe "episode 01"
            line(listOf("Alpha", "Beta")) shouldBe null
        }
    }
})
