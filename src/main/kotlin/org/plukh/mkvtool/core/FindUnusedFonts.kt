package org.plukh.mkvtool.core

import org.plukh.mkvtool.out.Advisory
import org.plukh.mkvtool.out.CommandResult
import org.plukh.mkvtool.out.Renderer
import java.io.File

/**
 * The `find-unused-fonts` engine: report every font in a `fonts/` subdirectory whose base name is never
 * referenced in any `.ass` or `.ssa` subtitle in the current directory.
 *
 * "Referenced" is v1's deliberately loose test: a case-insensitive substring match of the font's base
 * name (extension stripped) against the subtitle text. It over-reports usage rather than under-reports,
 * so a font is only listed when its name appears literally nowhere.
 */

/** The subtitle formats that declare fonts by name; compared against [extensionOf]. */
private val FONT_SUBTITLE_EXTENSIONS = setOf("ass", "ssa")

/** The run root: the base names of fonts that no subtitle references. */
data class FontUsageReport(val unusedFonts: List<String>) : CommandResult

/**
 * The pure matcher: return the [fontBaseNames] (original case) that appear in none of [subtitleLines].
 * [subtitleLines] are expected already-lower-cased; each font is lower-cased for the comparison and the
 * original case is preserved in the result, exactly as v1 did.
 */
fun unusedFonts(subtitleLines: List<String>, fontBaseNames: List<String>): List<String> =
    fontBaseNames.filter { font -> subtitleLines.none { it.contains(font.lowercase()) } }

/**
 * Scan [dir] for `.ass`/`.ssa` subtitles and a `fonts/` subdirectory, and report the unreferenced fonts.
 *
 * Subtitles are read with charset auto-detection ([readLinesDetected]). Font base names strip the last dot
 * segment, keeping dot-less names whole and (as in v1) listing any subdirectories of `fonts/` too.
 *
 * A missing `fonts/` directory made v1 crash (an NPE on `listFiles()` returning null). This reports the
 * absence as an advisory and returns an empty report (exit 0) instead.
 */
fun findUnusedFonts(dir: File, renderer: Renderer): FontUsageReport {
    val subtitleLines = (dir.listFiles() ?: emptyArray())
        .filter { it.isFile && extensionOf(it.name) in FONT_SUBTITLE_EXTENSIONS }
        .flatMap { readLinesDetected(it) }
        .map { it.lowercase() }

    val fontEntries = dir.resolve("fonts").listFiles()
    if (fontEntries == null) {
        renderer.render(Advisory("*** No fonts/ directory in the current directory"))
        return FontUsageReport(emptyList()).also { renderer.render(it) }
    }

    // Sorted for a deterministic listing — v1 relied on the platform's undefined listFiles order.
    val fontBaseNames = fontEntries.map { it.name.substringBeforeLast('.') }.sorted()
    return FontUsageReport(unusedFonts(subtitleLines, fontBaseNames)).also { renderer.render(it) }
}
