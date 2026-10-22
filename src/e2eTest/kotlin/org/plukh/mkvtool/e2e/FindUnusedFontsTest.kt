package org.plukh.mkvtool.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.plukh.mkvtool.e2e.support.Smoke
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.stageExternalText
import org.plukh.mkvtool.e2e.support.writeBytes

/** `find-unused-fonts` lists the fonts under `fonts/` that no `.ass` or `.ssa` subtitle in the directory mentions. */
class FindUnusedFontsTest : FunSpec({

    test("only the font no subtitle references is listed").config(tags = setOf(Smoke)) {
        val workDir = tempdir()
        stageExternalText(workDir, "subs.ass", "Style: Default,Cuprum,20\n")
        writeBytes(workDir, "fonts/Cuprum.ttf", ByteArray(0))
        writeBytes(workDir, "fonts/Unused Font.otf", ByteArray(0))

        val run = mkvtool("find-unused-fonts", workDir = workDir)
        run.exitCode shouldBe 0
        // Compared whole rather than by substring: the bare base names, one per line, are the command's
        // entire output and what a script consumes, so a prefix or a summary line would be a change.
        run.output.trimEnd().lines() shouldBe listOf("Unused Font")
    }

    test("a directory with no fonts/ says so and still exits 0") {
        val workDir = tempdir()
        stageExternalText(workDir, "subs.ass", "Style: Default,Cuprum,20\n")

        val run = mkvtool("find-unused-fonts", workDir = workDir)
        run.exitCode shouldBe 0
        run.output shouldContain "*** No fonts/ directory in the current directory"
    }
})
