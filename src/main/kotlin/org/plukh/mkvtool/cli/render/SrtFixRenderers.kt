package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.FileFix
import org.plukh.mkvtool.core.FixOutcome
import org.plukh.mkvtool.core.FixRun
import org.plukh.mkvtool.out.ResultTextRenderer

/**
 * One file `fix-srt` has been through. Verbatim to v1: a fixed file prints nothing here — only its
 * `*** Fixing` header, which is a diagnostics event — and a failure is a red `*** Error:` line on stderr.
 */
val FileFixRenderer = ResultTextRenderer<FileFix> { result, s ->
    when (val o = result.outcome) {
        FixOutcome.Fixed -> {
            // A fixed file prints only its header (a diagnostics event); nothing more here.
        }
        is FixOutcome.Failed ->
            s.err.println(s.red("*** Error: ${result.fileName}: ${o.message} (left unfixed)"))
    }
}

/**
 * The counts a `fix-srt` run ends with: green when clean, red on any failure. Omitted for an empty
 * directory — v1 printed no summary block there.
 */
val FixRunRenderer = ResultTextRenderer<FixRun> { result, s ->
    if (result.files.isNotEmpty()) {
        s.out.println()
        val text = "*** ${result.fixed} fixed, ${result.failed} failed"
        s.out.println(if (result.failed > 0) s.red(text) else s.green(text))
    }
}
