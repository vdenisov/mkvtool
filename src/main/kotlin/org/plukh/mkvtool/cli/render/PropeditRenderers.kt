package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.FileProped
import org.plukh.mkvtool.core.PropeditOutcome
import org.plukh.mkvtool.core.PropeditRun
import org.plukh.mkvtool.out.ResultTextRenderer

/**
 * One file mkvpropedit has been run against. Verbatim to v1: a processed file prints nothing here — only
 * its `*** Processing` header, which is a diagnostics event — and a failure is a red `*** Error:` line on
 * stderr, preceded by a blank stdout line.
 */
val FilePropedRenderer = ResultTextRenderer<FileProped> { result, s ->
    when (val o = result.outcome) {
        PropeditOutcome.Succeeded -> {
            // A processed file prints only its header (a diagnostics event); nothing more here.
        }
        is PropeditOutcome.Failed -> {
            s.out.println()
            s.err.println(s.red("*** Error: mkvpropedit exited with code ${o.exitCode}"))
        }
    }
}

/**
 * The counts a `propedit` run ends with — `*** <succeeded> processed, <failed> failed`, the first count
 * being `total - failed` exactly as v1 computed it. Green when clean, red on any failure.
 */
val PropeditRunRenderer = ResultTextRenderer<PropeditRun> { result, s ->
    s.out.println()
    val text = "*** ${result.total - result.failed} processed, ${result.failed} failed"
    s.out.println(if (result.failed > 0) s.red(text) else s.green(text))
}
