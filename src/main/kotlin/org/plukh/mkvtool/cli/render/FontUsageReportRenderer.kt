package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.FontUsageReport
import org.plukh.mkvtool.out.ResultTextRenderer

/**
 * The fonts nothing references — one bare base name per line, to stdout, with no prefix and no summary,
 * exactly as v1 printed them.
 */
val FontUsageReportRenderer = ResultTextRenderer<FontUsageReport> { result, s ->
    result.unusedFonts.forEach { s.out.println(it) }
}
