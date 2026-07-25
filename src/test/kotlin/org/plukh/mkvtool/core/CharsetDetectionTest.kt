package org.plukh.mkvtool.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Pins Groovy's `CharsetToolkit` guesser as reproduced in core/CharsetDetection.kt, including the two
 * quirks that decide real files: the 4 KB window and the unscanned six-byte tail.
 *
 * Every case passes windows-1251 as the fallback rather than taking the default, so the assertions say
 * what the algorithm decided instead of what the machine's `file.encoding` happens to be.
 */
class CharsetDetectionTest : FunSpec({

    val cp1251: Charset = Charset.forName("windows-1251")
    val utf8 = StandardCharsets.UTF_8

    data class Case(
        val label: String,
        val bytes: ByteArray,
        val expected: Charset,
    )

    context("detectCharset") {
        withData(
            nameFn = { it.label },
            Case(
                "a UTF-8 BOM decides on its own",
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hi".toByteArray(utf8),
                utf8,
            ),
            Case("a UTF-16LE BOM decides on its own", "hi".toByteArray(StandardCharsets.UTF_16LE).withBom(0xFF, 0xFE), StandardCharsets.UTF_16LE),
            Case("a UTF-16BE BOM decides on its own", "hi".toByteArray(StandardCharsets.UTF_16BE).withBom(0xFE, 0xFF), StandardCharsets.UTF_16BE),
            // No high byte anywhere, so nothing distinguishes ASCII from the default charset — v1 keeps
            // enforce8Bit on and answers with the default rather than US-ASCII.
            Case("pure ASCII falls back", "plain ascii, no high bytes\n".toByteArray(utf8), cp1251),
            Case("empty falls back", ByteArray(0), cp1251),
            Case("valid UTF-8 multi-byte wins", "Русский текст, длиннее семи байт\n".toByteArray(utf8), utf8),
            Case("single-byte high bytes fall back", "Русский текст, длиннее семи байт\n".toByteArray(cp1251), cp1251),
            // The scan stops six bytes short of the end, so a six-byte file is judged pure ASCII no matter
            // what it holds, and a seventh byte is what makes its first byte visible at all — v1's
            // behavior, reproduced deliberately.
            Case("a six-byte UTF-8 file falls back (the unscanned tail)", "Тес".toByteArray(utf8), cp1251),
            Case("a seventh byte brings the first one into the scan", "Тabcde".toByteArray(utf8), utf8),
            // Detection sees the first DETECTION_WINDOW bytes only; Cyrillic after them is invisible.
            Case(
                "high bytes past the 4 KB window are invisible",
                ("x".repeat(DETECTION_WINDOW) + "Русский\n").toByteArray(utf8),
                cp1251,
            ),
            Case(
                "high bytes inside the 4 KB window are seen",
                ("x".repeat(DETECTION_WINDOW - 64) + "Русский\n").toByteArray(utf8),
                utf8,
            ),
            // A truncated lead byte is not valid UTF-8: the scan gives up and answers with the fallback.
            Case(
                "a broken sequence falls back",
                "prefix".toByteArray(utf8) + byteArrayOf(0xD0.toByte(), 0x41) + "suffix".toByteArray(utf8),
                cp1251,
            ),
        ) { detectCharset(it.bytes, cp1251) shouldBe it.expected }
    }

    context("readTextDetected") {
        test("strips a UTF-8 BOM, as Groovy's reader does") {
            val dir = tempdir()
            val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "show: Тест\n".toByteArray(utf8)
            readTextDetected(write(dir, "bom.yaml", bytes), cp1251) shouldBe "show: Тест\n"
        }

        test("strips a UTF-16LE BOM and decodes the rest") {
            val dir = tempdir()
            val bytes = "Тест\n".toByteArray(StandardCharsets.UTF_16LE).withBom(0xFF, 0xFE)
            readTextDetected(write(dir, "utf16.txt", bytes), cp1251) shouldBe "Тест\n"
        }

        test("decodes single-byte content with the fallback") {
            val dir = tempdir()
            val text = "Русский текст, длиннее семи байт\n"
            readTextDetected(write(dir, "cp1251.txt", text.toByteArray(cp1251)), cp1251) shouldBe text
        }

        test("decodes leniently rather than refusing, exactly as InputStreamReader does") {
            val dir = tempdir()
            // Detected as UTF-8 (the sequence is valid) but decoded as an unrelated charset would replace,
            // so pin the lenient side: an invalid tail under a UTF-8 verdict becomes U+FFFD, not a throw.
            val bytes = "Русский текст, длиннее семи байт".toByteArray(utf8) + byteArrayOf(0xC3.toByte())
            readTextDetected(write(dir, "mixed.txt", bytes), cp1251).endsWith("�") shouldBe true
        }

        test("an empty file reads as empty text") {
            val dir = tempdir()
            readTextDetected(write(dir, "empty.txt", ByteArray(0)), cp1251) shouldBe ""
        }
    }

    context("readLinesDetected") {
        test("splits on LF, CRLF and CR, dropping the trailing terminator") {
            val dir = tempdir()
            val file = write(dir, "lines.txt", "one\ntwo\r\nthree\rfour\n".toByteArray(utf8))
            readLinesDetected(file, cp1251) shouldBe listOf("one", "two", "three", "four")
        }

        test("keeps interior blank lines and drops only the final empty one") {
            val dir = tempdir()
            val file = write(dir, "blanks.txt", "one\n\ntwo\n".toByteArray(utf8))
            readLinesDetected(file, cp1251) shouldBe listOf("one", "", "two")
        }

        test("a BOM does not leak into the first line") {
            val dir = tempdir()
            val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "first\nsecond\n".toByteArray(utf8)
            readLinesDetected(write(dir, "bom.txt", bytes), cp1251) shouldBe listOf("first", "second")
        }
    }
})

private fun write(dir: File, name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

/** Prefix content bytes with a byte-order mark; `toByteArray(UTF_16LE/BE)` emits none of its own. */
private fun ByteArray.withBom(vararg bom: Int): ByteArray =
    ByteArray(bom.size) { bom[it].toByte() } + this
