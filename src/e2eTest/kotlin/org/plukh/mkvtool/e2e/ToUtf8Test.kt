package org.plukh.mkvtool.e2e

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.writeText
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * `to-utf8` converts subtitle files in place.
 *
 * This case needs no external tool at all — it is bytes in, bytes out through the CLI — which makes it
 * the one that proves the invoker itself, and, on a native binary, that `-H:+AddAllCharsets` survived
 * into the image: without it `Charset.forName("windows-1251")` throws.
 */
class ToUtf8Test : FunSpec({

    val windows1251: Charset = Charset.forName("windows-1251")

    test("windows-1251 becomes UTF-8 in place, and line endings survive") {
        val workDir = tempdir()
        val lf = writeText(workDir, "lf.srt", "Привет, мир!\n", windows1251)
        val crlf = writeText(workDir, "crlf.srt", "Привет\r\nмир\r\n", windows1251)

        val run = mkvtool("to-utf8", workDir = workDir)
        run.exitCode shouldBe 0
        run.output shouldContain "windows-1251"

        lf.readText(StandardCharsets.UTF_8) shouldBe "Привет, мир!\n"
        // Decoding and re-encoding the content whole rather than line by line is what keeps these; a
        // line-by-line rewrite would normalise them to the platform separator.
        crlf.readText(StandardCharsets.UTF_8) shouldBe "Привет\r\nмир\r\n"
    }
})
