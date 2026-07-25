package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * The column-grid helpers, now shared by the check report and (from 4.1) by `--identify`. Their edges are
 * what break a table: an unclamped limit, a name one character too long, a padded cell that stops lining
 * up.
 */
class TextTableTest : FunSpec({

    context("formatFileList") {
        test("indents every name, one per line") {
            formatFileList(listOf("a.mkv", "b.mkv"), "    ") shouldContainExactly
                listOf("    a.mkv", "    b.mkv")
        }

        test("truncates past the limit and says how many were dropped") {
            formatFileList((1..5).map { "e$it.mkv" }, "  ", limit = 2) shouldContainExactly
                listOf("  e1.mkv", "  e2.mkv", "  ... and 3 more")
        }

        test("Int.MAX_VALUE means no limit, not a 2-billion-element array") {
            // --check-verbose passes exactly this; take(MAX) allocates up front, so the limit is clamped
            // against the list size before anything is taken.
            formatFileList(listOf("a.mkv"), "  ", limit = Int.MAX_VALUE) shouldContainExactly listOf("  a.mkv")
        }

        test("an empty list produces no lines at all, not an empty indent") {
            formatFileList(emptyList(), "  ") shouldContainExactly emptyList()
        }
    }

    context("fitName") {
        test("leaves a name that fits, including one exactly at the width") {
            fitName("Japanese", 12) shouldBe "Japanese"
            fitName("123456789012", 12) shouldBe "123456789012"
        }

        test("truncates with an ASCII ellipsis, ending exactly at the width") {
            val fitted = fitName("A very long track name indeed", 12)
            fitted shouldBe "A very lo..."
            fitted.length shouldBe 12
        }
    }

    test("shortType abbreviates only subtitles, the one type that would widen the column") {
        shortType("subtitles") shouldBe "subs"
        shortType("audio") shouldBe "audio"
        shortType("video") shouldBe "video"
    }

    context("pad") {
        test("pads to the column width and renders null as an empty cell") {
            pad("eng", 5) shouldBe "eng  "
            pad(null, 3) shouldBe "   "
            pad(7, 4) shouldBe "7   "
        }

        test("leaves an over-wide value alone rather than cutting a column short") {
            pad("subtitles", 6) shouldBe "subtitles"
        }
    }
})
