package org.plukh.mkvtool.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvpropedit
import org.plukh.mkvtool.e2e.support.probe
import org.plukh.mkvtool.e2e.support.stageInput

/**
 * `propedit` forwards every argument to mkvpropedit verbatim, with the file name inserted first, so
 * anything mkvpropedit accepts works with no code change here.
 *
 * The case is skipped rather than failed where mkvpropedit is absent: it is genuinely optional on a
 * developer machine, and a red case there would say nothing about the tool.
 */
class PropeditTest : FunSpec({

    test("arguments reach mkvpropedit unchanged").config(enabledOrReasonIf = needsMkvpropedit) {
        val workDir = tempdir()
        val input = stageInput(workDir)

        val run = mkvtool("propedit", "--edit", "info", "--set", "title=SmokeTest", workDir = workDir)
        run.exitCode shouldBe 0

        // Read back through the production parser, which is what makes this an assertion about the file
        // rather than about what the command printed.
        probe(input).containerTitle shouldBe "SmokeTest"
    }
})
