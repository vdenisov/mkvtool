package org.plukh.mkvtool.e2e.support

import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Staging files a case runs against.
 *
 * Every case gets a temp directory of its own from Kotest's `tempdir()`, which is what lets this tier run
 * its cases concurrently: nothing here is shared between cases.
 */

/** Copy the one input fixture into [workDir] under [name], creating parent directories. */
fun stageInput(workDir: File, name: String = "test.mkv"): File {
    val dest = File(workDir, name)
    dest.parentFile?.mkdirs()
    testMkv.copyTo(dest, overwrite = true)
    return dest
}

/**
 * Write `config.yaml` into [workDir], where `mux` reads it from.
 *
 * Explicit UTF-8, as on every read and write in this project, so a Cyrillic title never depends on a
 * default.
 */
fun writeConfig(workDir: File, yaml: String): File =
    File(workDir, "config.yaml").also { it.writeText(yaml, StandardCharsets.UTF_8) }

/**
 * The two byte-order marks the `to-utf8` cases build inputs from.
 *
 * A call site prepends one with `UTF8_BOM + text.toByteArray(...)`.
 */
val UTF8_BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
val UTF16_BOM: ByteArray = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

/** Write raw bytes into [workDir] — for the charset cases, where the point is what is on disk. */
fun writeBytes(workDir: File, name: String, bytes: ByteArray): File =
    File(workDir, name).also { it.parentFile?.mkdirs(); it.writeBytes(bytes) }

/** Write text in a named charset, for the same reason. */
fun writeText(workDir: File, name: String, text: String, charset: Charset): File =
    writeBytes(workDir, name, text.toByteArray(charset))

/** The single `.mkv` a mux run produced, or null. */
fun findOutput(workDir: File, destDir: String = "mkv"): File? =
    File(workDir, destDir).listFiles()?.firstOrNull { it.name.endsWith(".mkv") }

/**
 * Every `.mkv` a mux run produced, by name, sorted — for the batch cases.
 *
 * Filters to `.mkv`: every assertion resting on it is about muxed output; a case wanting to assert that the
 * destination directory holds *nothing else* needs a plain directory listing rather than this.
 */
fun outputNames(workDir: File, destDir: String = "mkv"): List<String> =
    File(workDir, destDir).listFiles().orEmpty().filter { it.name.endsWith(".mkv") }.map { it.name }.sorted()
