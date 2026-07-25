package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.FileTitled
import org.plukh.mkvtool.core.TitleOutcome
import org.plukh.mkvtool.core.TitleRun
import org.plukh.mkvtool.out.ResultTextRenderer

/**
 * One file `filename-to-title` has retitled. Verbatim to v1: a successful file prints nothing here — only
 * its `*** Processing` header, which is a diagnostics event — and a failure is a red `*** Error:` line on
 * stderr, preceded by a blank stdout line.
 *
 * [FileTitled.title] is deliberately not rendered: v1 prints no title line, and the field is there so the
 * result document is complete for a machine-readable renderer, not for the text one. Do not "fix" this.
 */
val FileTitledRenderer = ResultTextRenderer<FileTitled> { result, s ->
    when (val o = result.outcome) {
        TitleOutcome.Succeeded -> {
            // A processed file prints only its header (a diagnostics event); nothing more here.
        }
        is TitleOutcome.Failed -> {
            s.out.println()
            s.err.println(s.red("*** Error: mkvpropedit exited with code ${o.exitCode}"))
        }
    }
}

/**
 * The counts a `filename-to-title` run ends with — `*** <succeeded> processed, <failed> failed`, the
 * first count being `total - failed` exactly as v1 computed it. Green when clean, red on any failure.
 */
val TitleRunRenderer = ResultTextRenderer<TitleRun> { result, s ->
    s.out.println()
    val text = "*** ${result.total - result.failed} processed, ${result.failed} failed"
    s.out.println(if (result.failed > 0) s.red(text) else s.green(text))
}
