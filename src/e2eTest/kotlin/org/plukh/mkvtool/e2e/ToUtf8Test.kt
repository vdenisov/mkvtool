package org.plukh.mkvtool.e2e

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.e2e.support.Smoke
import org.plukh.mkvtool.e2e.support.UTF16_BOM
import org.plukh.mkvtool.e2e.support.UTF8_BOM
import org.plukh.mkvtool.e2e.support.mkvtool
import org.plukh.mkvtool.e2e.support.writeBytes
import org.plukh.mkvtool.e2e.support.writeText
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * `to-utf8` converts subtitle files in place.
 *
 * None of these cases needs an external tool — it is bytes in, bytes out through the CLI — and on a
 * native binary they prove `-H:+AddAllCharsets` survived into the image: without it `Charset.forName`
 * throws for every charset but the default.
 */
class ToUtf8Test : FunSpec({

    val windows1251: Charset = Charset.forName("windows-1251")

    test("windows-1251 becomes UTF-8 in place, and line endings survive").config(tags = setOf(Smoke)) {
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

    context("what each file ends up as") {
        val cyrillic1251 = "Привет\n".toByteArray(windows1251)
        val cyrillicUtf8 = "Привет\n".toByteArray(StandardCharsets.UTF_8)
        // Not valid UTF-8, and not valid Shift_JIS either: a lead byte followed by an invalid trail byte.
        val invalidShiftJis = byteArrayOf(0x81.toByte(), 0x20, 0x41)

        withData(
            nameFn = { it.label },
            ToUtf8Row(
                // Double-converting is how a previously fixed file gets ruined, which is what makes the
                // command safe to point at a directory more than once.
                "already-UTF-8 input is skipped byte for byte, by BOM or by a clean decode",
                staged = mapOf(
                    "plain.srt" to cyrillicUtf8,
                    "bom.ass" to UTF8_BOM + cyrillicUtf8,
                    "ascii.vtt" to "WEBVTT\n\nplain\n".toByteArray(StandardCharsets.UTF_8),
                ),
                contains = listOf("3 skipped"),
            ),
            ToUtf8Row(
                // .sub is MicroDVD text or the binary half of a VobSub pair, and rewriting the binary one
                // would destroy it — so the extension is not even considered.
                "all four text formats convert and a .sub is left alone",
                staged = listOf("srt", "ass", "ssa", "vtt").associate { "sub.$it" to cyrillic1251 } +
                    ("movie.sub" to byteArrayOf(0x00, 0x00, 0x01, 0xBA.toByte(), 0x44)),
                contains = listOf("4 converted"),
                notContains = listOf("movie.sub"),
                after = listOf("srt", "ass", "ssa", "vtt").associate { "sub.$it" to cyrillicUtf8 },
            ),
            ToUtf8Row(
                "--encoding selects the source charset",
                staged = mapOf("cz.srt" to byteArrayOf(0xC8.toByte(), 0x61, 0x6A)),
                args = listOf("--encoding", "windows-1250"),
                contains = listOf("windows-1250"),
                after = mapOf("cz.srt" to "Čaj".toByteArray(StandardCharsets.UTF_8)),
            ),
            ToUtf8Row(
                "an unusable charset name exits 2 before any file is touched",
                staged = mapOf("a.srt" to cyrillic1251),
                args = listOf("--encoding", "not-a-charset"),
                exit = 2,
            ),
            ToUtf8Row(
                // A replacing decoder would write mojibake and call it success; strict decoding is what
                // turns a wrong --encoding into a visible failure.
                "bytes invalid in the source charset are refused, not mangled",
                staged = mapOf("bad.srt" to invalidShiftJis),
                args = listOf("--encoding", "Shift_JIS"),
                exit = 1,
                contains = listOf("not valid Shift_JIS"),
            ),
            ToUtf8Row(
                "--backup keeps the original bytes alongside",
                staged = mapOf("a.srt" to cyrillic1251),
                args = listOf("--backup"),
                after = mapOf("a.srt" to cyrillicUtf8, "a.srt.orig" to cyrillic1251),
            ),
            ToUtf8Row(
                "--dry-run reports without writing anything",
                staged = mapOf("a.srt" to cyrillic1251),
                args = listOf("--dry-run"),
                contains = listOf("would convert"),
                after = mapOf("a.srt.orig" to null),
            ),
            ToUtf8Row(
                // Every byte of UTF-16 maps to something in a single-byte charset, so a strict decode alone
                // would succeed and write the mojibake back.
                "UTF-16 is recognised and left alone",
                staged = mapOf(
                    "wide.ssa" to UTF16_BOM + "Привет\n".toByteArray(StandardCharsets.UTF_16LE),
                ),
                contains = listOf("UTF-16"),
            ),
        ) { row ->
            val workDir = tempdir()
            row.staged.forEach { (name, bytes) -> writeBytes(workDir, name, bytes) }

            val run = mkvtool("to-utf8", *row.args.toTypedArray(), workDir = workDir)

            withClue(run.output) {
                run.exitCode shouldBe row.exit
                row.contains.forEach { run.output shouldContain it }
                row.notContains.forEach { run.output shouldNotContain it }
                // A staged file the row says nothing about must come out exactly as it went in.
                (row.staged + row.after).forEach { (name, expected) ->
                    val file = File(workDir, name)
                    if (expected == null) file.exists() shouldBe false else file.readBytes() shouldBe expected
                }
            }
        }
    }

    test("the summary is coloured by outcome, with the escapes at the line edges") {
        val workDir = tempdir()
        val esc = "\u001B"
        writeText(workDir, "ok.srt", "Привет\n", windows1251)

        val clean = mkvtool("to-utf8", "--color", "always", workDir = workDir)
        clean.exitCode shouldBe 0
        // Whole-line colouring is what keeps every pinned substring of the plain summary intact.
        clean.output shouldContain "$esc[32m*** 1 converted, 0 skipped, 0 failed$esc[0m"

        // ok.srt is UTF-8 by now, so this run skips it and fails on the newcomer alone.
        writeBytes(workDir, "bad.srt", byteArrayOf(0x81.toByte(), 0x20, 0x41))
        val failed = mkvtool("to-utf8", "--encoding", "Shift_JIS", "--color", "always", workDir = workDir)
        failed.exitCode shouldBe 1
        failed.output shouldContain "$esc[31m"
        failed.output shouldContain "1 failed"
    }
})

/** One `to-utf8` run: what is staged, how it is invoked, and what must hold afterwards. */
private class ToUtf8Row(
    val label: String,
    val staged: Map<String, ByteArray>,
    val args: List<String> = emptyList(),
    val exit: Int = 0,
    val contains: List<String> = emptyList(),
    val notContains: List<String> = emptyList(),
    // Expected bytes per file name, or null for a file that must not exist; overrides the staged bytes.
    val after: Map<String, ByteArray?> = emptyMap(),
)
