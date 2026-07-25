package org.plukh.mkvtool.core

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Groovy's charset auto-detection, reproduced.
 *
 * Four reads deliberately name no charset — `config.yaml`, `episodes.txt`, and the subtitles that
 * `fix-srt` and `find-unused-fonts` scan — because the Groovy scripts read them through the no-arg reader
 * (`file.text`, `file.readLines()`), which guesses via `groovy.util.CharsetToolkit`. All four are
 * hand-written or hand-assembled files, so forcing UTF-8 on them would be a contract change rather than a
 * cleanup (CLAUDE.md, "Groovy's File I/O is charset-asymmetric"). This is that guesser, transcribed from
 * the Groovy 3.0.x source rather than reinvented: the 4 KB window, the unscanned six-byte tail and the
 * sequence-length increments are all observable behavior.
 *
 * The [fallback] is what CharsetToolkit calls the default charset, and it is a parameter rather than a
 * constant so a call site can override it. Note what it means today: since JEP 400 (JDK 18+)
 * `Charset.defaultCharset()` is UTF-8 on every platform, so the fallback no longer means "the OS charset",
 * and a hand-typed windows-1251 file decodes to U+FFFD here — as it does under the Groovy scripts on the
 * same JDK. Rescuing legacy single-byte input is `to-utf8`'s job, not this one's.
 */

/** CharsetToolkit guesses from the first 4 KB only; the rest of the file is then decoded with that guess. */
const val DETECTION_WINDOW = 4096

/** The BOM code point. Present in the decoded text exactly when the bytes carried a BOM. */
private const val BOM = '﻿'

/**
 * Guess the charset of [bytes] the way Groovy does: a BOM wins outright; otherwise the window is scanned
 * for bytes with the high bit set, and the answer is UTF-8 when every one of them leads a valid multi-byte
 * sequence, [fallback] when any does not. Bytes with no high bit at all — pure ASCII — yield [fallback]
 * too, because CharsetToolkit's `enforce8Bit` defaults to true; US-ASCII would decode them identically.
 */
fun detectCharset(bytes: ByteArray, fallback: Charset = Charset.defaultCharset()): Charset {
    val buffer = if (bytes.size > DETECTION_WINDOW) bytes.copyOf(DETECTION_WINDOW) else bytes
    bomCharset(buffer)?.let { return it }

    var highOrderBit = false
    var validU8Char = true
    var i = 0
    // The bound is CharsetToolkit's, not an off-by-one: it reads six bytes per iteration and so never
    // examines the last six of the window. A file of six bytes or fewer is therefore judged pure ASCII
    // whatever it holds. Reproduced because it is what decides the charset of a very short episodes.txt.
    while (i < buffer.size - 6) {
        val b0 = buffer[i]
        if (b0 < 0) {
            highOrderBit = true
            val sequence = sequenceLength(b0)
            if (sequence == 0 || !(1 until sequence).all { isContinuationChar(buffer[i + it]) }) {
                validU8Char = false
            } else {
                i += sequence - 1
            }
        }
        if (!validU8Char) break
        i++
    }

    if (!highOrderBit) return fallback
    return if (validU8Char) StandardCharsets.UTF_8 else fallback
}

/**
 * Read [file] whole with the charset [detectCharset] guesses for it, dropping a leading BOM as Groovy's
 * reader does. Decoding is lenient — bytes that are invalid in the guessed charset become U+FFFD rather
 * than an exception, which is what `InputStreamReader` does. [strictDecode] is the opposite tool, for
 * `to-utf8`, where refusing to produce mojibake is the whole feature.
 */
fun readTextDetected(file: File, fallback: Charset = Charset.defaultCharset()): String {
    val bytes = file.readBytes()
    val text = String(bytes, detectCharset(bytes, fallback))
    // v1 skips one character when it saw a BOM; a BOM always decodes to exactly this one, so testing the
    // decoded text is the same rule without carrying the sniff result around.
    return if (text.startsWith(BOM)) text.substring(1) else text
}

/**
 * Read [file] as lines, with [readTextDetected]'s charset handling and `BufferedReader.readLine`
 * semantics — `\n`, `\r\n` and `\r` all end a line, and a trailing line terminator does not produce a
 * final empty element. This is what Groovy's no-arg `readLines()` returns.
 */
fun readLinesDetected(file: File, fallback: Charset = Charset.defaultCharset()): List<String> =
    readTextDetected(file, fallback).reader().readLines()

/** The charset a leading byte-order mark names, or null when there is none. Checked in CharsetToolkit's
 *  order: UTF-8, then UTF-16LE, then UTF-16BE. */
private fun bomCharset(bytes: ByteArray): Charset? = when {
    startsWith(bytes, 0xEF, 0xBB, 0xBF) -> StandardCharsets.UTF_8
    startsWith(bytes, 0xFF, 0xFE) -> StandardCharsets.UTF_16LE
    startsWith(bytes, 0xFE, 0xFF) -> StandardCharsets.UTF_16BE
    else -> null
}

/** How many bytes the UTF-8 sequence led by [b] occupies, or 0 when [b] cannot lead one. The ranges are
 *  CharsetToolkit's, in signed form — a continuation byte or `FE`/`FF` in lead position lands in the 0 case. */
private fun sequenceLength(b: Byte): Int = when (b) {
    in (-64).toByte()..(-33).toByte() -> 2
    in (-32).toByte()..(-17).toByte() -> 3
    in (-16).toByte()..(-9).toByte() -> 4
    in (-8).toByte()..(-5).toByte() -> 5
    in (-4).toByte()..(-3).toByte() -> 6
    else -> 0
}

/** True for a UTF-8 continuation byte `10xxxxxx`, i.e. `0x80`–`0xBF` in signed form. */
private fun isContinuationChar(b: Byte): Boolean = b <= -65

/** True when [bytes] begins with the unsigned byte sequence [prefix] (JVM bytes are signed; mask to 0–255). */
internal fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean =
    bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 0xFF) == prefix[it] }
