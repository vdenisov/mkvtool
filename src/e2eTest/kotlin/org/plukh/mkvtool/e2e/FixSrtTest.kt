package org.plukh.mkvtool.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.e2e.support.Smoke
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.writeText
import java.io.File
import java.nio.charset.StandardCharsets

/** `fix-srt` rewrites legacy `hh:mm:ss.cc,hh:mm:ss.cc` subtitles as numbered SRT cues in `<name>.fixed`. */
class FixSrtTest : FunSpec({

    test("a legacy file becomes numbered cues with arrow timing").config(tags = setOf(Smoke)) {
        val workDir = tempdir()
        writeText(
            workDir,
            "ep.srt",
            "00:01:41.42,00:01:42.30\nLine one[br]Line two\n\n00:01:43.00,00:01:44.00\nAnother line\n\n",
            StandardCharsets.UTF_8,
        )

        val run = mkvtool("fix-srt", workDir = workDir)
        run.exitCode shouldBe 0
        run.output shouldContain "Fixing ep.srt"
        run.output shouldContain "1 fixed, 0 failed"

        val lines = File(workDir, "ep.srt.fixed").readLines(StandardCharsets.UTF_8)
        lines[0] shouldBe "1"
        lines[1] shouldBe "00:01:41,420 --> 00:01:42,300"
        lines[2] shouldBe "Line one"
        lines[3] shouldBe "Line two"
        lines[5] shouldBe "2"
    }

    test("a malformed file is reported and skipped, the rest are fixed, and the run exits 1") {
        val workDir = tempdir()
        writeText(
            workDir,
            "bad.srt",
            "00:01:41.42,00:01:42.30\nText\nnot-blank where a blank line belongs\n",
            StandardCharsets.UTF_8,
        )
        writeText(workDir, "good.srt", "00:01:43.00,00:01:44.00\nFine\n\n", StandardCharsets.UTF_8)

        val run = mkvtool("fix-srt", workDir = workDir)
        run.exitCode shouldBe 1
        // The file name alone would match its `*** Fixing` header; the error line has to carry it too.
        run.output shouldContain "*** Error: bad.srt:"
        // One bad file once killed the whole run with a stack trace.
        run.output shouldNotContain "Exception"
        run.output shouldContain "1 fixed, 1 failed"

        File(workDir, "good.srt.fixed").exists() shouldBe true
        File(workDir, "bad.srt.fixed").exists() shouldBe false
    }
})
