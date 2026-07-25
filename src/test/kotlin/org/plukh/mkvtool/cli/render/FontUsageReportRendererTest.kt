package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import org.plukh.mkvtool.core.FontUsageReport

/**
 * `find-unused-fonts` prints one bare font base name per line to stdout — no prefix, no summary, no
 * color — exactly as v1 did.
 */
class FontUsageReportRendererTest : FunSpec({

    fun render(result: FontUsageReport) =
        renderResult(FontUsageReportRenderer, result, colorEnabled = true)

    test("each unused font is a bare line to stdout, no color or prefix") {
        val (out, err) = render(FontUsageReport(listOf("Comic", "Verdana")))
        // Bare names, in order, one per line; trimEnd/lines keeps this platform line-separator agnostic.
        out.trimEnd().lines() shouldBe listOf("Comic", "Verdana")
        err.shouldBeEmpty()
    }

    test("an empty report prints nothing") {
        val (out, err) = render(FontUsageReport(emptyList()))
        out.shouldBeEmpty()
        err.shouldBeEmpty()
    }
})
