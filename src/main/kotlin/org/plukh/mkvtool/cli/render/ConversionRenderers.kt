package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.ConversionRun
import org.plukh.mkvtool.core.FileConversion
import org.plukh.mkvtool.core.FileOutcome
import org.plukh.mkvtool.out.ResultTextRenderer

/**
 * One converted (or skipped, or failed) file, as `to-utf8` reports it while it works. Command logic never
 * composes this prose; it lives here and is bound to [FileConversion] in the one result table.
 *
 * Coloring is deliberate and verbatim to v1: the skip/convert lines and the invalid-source hint are bare
 * (uncolored); only the invalid-source `*** Error:` line carries color, in red, on stderr.
 */
val FileConversionRenderer = ResultTextRenderer<FileConversion> { result, s ->
    when (val o = result.outcome) {
        FileOutcome.Utf16Bom ->
            s.out.println("*** ${result.fileName}: looks like UTF-16 (BOM), leaving it alone")
        FileOutcome.Utf8Bom ->
            s.out.println("*** ${result.fileName}: already UTF-8 (BOM), skipping")
        FileOutcome.Utf8Clean ->
            s.out.println("*** ${result.fileName}: already valid UTF-8, skipping")
        is FileOutcome.WouldConvert ->
            s.out.println("*** ${result.fileName}: would convert from ${o.charsetName} to UTF-8")
        is FileOutcome.Converted -> {
            if (o.backupName != null) s.out.println("*** ${result.fileName}: backed up as ${o.backupName}")
            s.out.println("*** ${result.fileName}: converted from ${o.charsetName} to UTF-8")
        }
        is FileOutcome.NotValidSource -> {
            s.err.println(s.red("*** Error: ${result.fileName}: not valid ${o.charsetName} (${o.exceptionName}), leaving it alone"))
            s.err.println("      Pass the right --encoding; converting anyway would produce mojibake.")
        }
    }
}

/**
 * The counts a `to-utf8` run ends with: green when clean, red on any failure. Omitted entirely for an
 * empty directory — v1 printed no summary block when there were no files, so neither the blank line nor
 * the counts appear.
 */
val ConversionRunRenderer = ResultTextRenderer<ConversionRun> { result, s ->
    if (result.files.isNotEmpty()) {
        s.out.println()
        val text = "*** ${result.converted} converted, ${result.skipped} skipped, ${result.failed} failed"
        s.out.println(if (result.failed > 0) s.red(text) else s.green(text))
    }
}
