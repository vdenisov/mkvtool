package org.plukh.mkvtool.cli.render

import org.plukh.mkvtool.core.CheckReport
import org.plukh.mkvtool.core.ConversionRun
import org.plukh.mkvtool.core.EpisodeFetch
import org.plukh.mkvtool.core.FileConversion
import org.plukh.mkvtool.core.FileFix
import org.plukh.mkvtool.core.FileProped
import org.plukh.mkvtool.core.FileTitled
import org.plukh.mkvtool.core.FixRun
import org.plukh.mkvtool.core.FontUsageReport
import org.plukh.mkvtool.core.PropeditRun
import org.plukh.mkvtool.core.ShowFetched
import org.plukh.mkvtool.core.TitleRun
import org.plukh.mkvtool.core.TranslationFallback
import org.plukh.mkvtool.out.RenderHints
import org.plukh.mkvtool.out.ResultRendererRegistry

/**
 * Every result type the application can emit, bound to the renderer that draws it as text. This is the
 * whole table: adding a command means adding its result types here, and a type that is not here fails
 * loudly the first time it is emitted (see [ResultRendererRegistry]) rather than vanishing.
 *
 * The table is the reason a command cannot decide how its results look. It hands the seam data and the
 * type decides the rest — which is what keeps the check report identical in `inspect` and in `mux`.
 *
 * [hints] reaches the few renderers that take a setting; everything else ignores it.
 */
fun textResultRenderers(hints: RenderHints = RenderHints()): ResultRendererRegistry =
    ResultRendererRegistry()
        .register(FileConversion::class, FileConversionRenderer)
        .register(ConversionRun::class, ConversionRunRenderer)
        .register(FileFix::class, FileFixRenderer)
        .register(FixRun::class, FixRunRenderer)
        .register(FileProped::class, FilePropedRenderer)
        .register(PropeditRun::class, PropeditRunRenderer)
        .register(FileTitled::class, FileTitledRenderer)
        .register(TitleRun::class, TitleRunRenderer)
        .register(FontUsageReport::class, FontUsageReportRenderer)
        .register(ShowFetched::class, ShowFetchedRenderer)
        .register(TranslationFallback::class, TranslationFallbackRenderer)
        .register(EpisodeFetch::class, EpisodeFetchRenderer)
        .register(CheckReport::class, CheckReportRenderer(hints.verboseFileLists))
