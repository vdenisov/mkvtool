package org.plukh.mkvtool.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.e2e.support.Smoke
import org.plukh.mkvtool.e2e.support.exec
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.needsMkvpropedit
import org.plukh.mkvtool.e2e.support.posixOnly
import org.plukh.mkvtool.e2e.support.probe
import org.plukh.mkvtool.e2e.support.stageInput
import org.plukh.mkvtool.e2e.support.writeText
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * `propedit` forwards every argument to mkvpropedit verbatim, with the file name inserted first, so
 * anything mkvpropedit accepts works with no code change here.
 *
 * Cases that run mkvpropedit are skipped rather than failed where it is absent: it is genuinely optional on
 * a developer machine, and a red case there would say nothing about the tool.
 */
class PropeditTest : FunSpec({

    test("arguments reach mkvpropedit unchanged")
        .config(tags = setOf(Smoke), enabledOrReasonIf = needsMkvpropedit) {
        val workDir = tempdir()
        val input = stageInput(workDir)

        val run = mkvtool("propedit", "--edit", "info", "--set", "title=SmokeTest", workDir = workDir)
        run.exitCode shouldBe 0
        run.output shouldContain "*** Processing test.mkv"

        // Read back through the production parser, which is what makes this an assertion about the file
        // rather than about what the command printed.
        probe(input).containerTitle shouldBe "SmokeTest"
    }

    test("no arguments prints usage and touches nothing") {
        val workDir = tempdir()
        val input = stageInput(workDir)
        val size = input.length()
        val modified = input.lastModified()

        val run = mkvtool("propedit", workDir = workDir)
        run.exitCode shouldBe 0
        run.output shouldContain "Usage"

        input.length() shouldBe size
        input.lastModified() shouldBe modified
    }

    test("a file mkvpropedit rejects is reported, counted, and exits 1")
        .config(enabledOrReasonIf = needsMkvpropedit) {
        val workDir = tempdir()
        writeText(workDir, "bad.mkv", "not an mkv at all", StandardCharsets.UTF_8)

        val run = mkvtool("propedit", "--edit", "info", "--set", "title=X", workDir = workDir)
        run.exitCode shouldBe 1
        run.output shouldContain "*** Error:"
        run.output shouldContain "0 processed, 1 failed"
    }

    // A stub that fails `--version` shadows the real mkvpropedit, which is the same "not usable" branch an
    // absent one takes. PATH cannot simply be emptied: the POSIX launchers need sed, xargs and dirname.
    test("a missing mkvpropedit exits 2 before touching any file").config(enabledOrReasonIf = posixOnly) {
        val workDir = tempdir()
        val input = stageInput(workDir)
        val size = input.length()
        val modified = input.lastModified()

        val stub = File(tempdir(), "mkvpropedit")
        stub.writeText("#!/bin/sh\nexit 1\n")
        check(stub.setExecutable(true)) { "could not make $stub executable" }
        // A stub the system will not execute (a noexec temp directory) is passed over silently in favour of
        // the real tool, which would fail this case for a reason that has nothing to do with mkvtool.
        exec(listOf(stub.absolutePath)).exitCode shouldBe 1

        val path = stub.parentFile.absolutePath + File.pathSeparator + System.getenv("PATH")
        val run = mkvtool(
            "propedit", "--edit", "info", "--set", "title=X",
            workDir = workDir,
            env = mapOf("PATH" to path),
        )
        run.exitCode shouldBe 2
        run.output shouldContain "*** Error: 'mkvpropedit' not found on PATH or in default install location"
        run.output shouldNotContain "*** Processing"

        input.length() shouldBe size
        input.lastModified() shouldBe modified
    }
})
