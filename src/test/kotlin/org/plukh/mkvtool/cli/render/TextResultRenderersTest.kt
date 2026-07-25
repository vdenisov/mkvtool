package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.plukh.mkvtool.core.CheckReport
import org.plukh.mkvtool.core.ConversionRun
import org.plukh.mkvtool.core.EpisodeFetch
import org.plukh.mkvtool.core.FileConversion
import org.plukh.mkvtool.core.FileFix
import org.plukh.mkvtool.core.FileProped
import org.plukh.mkvtool.core.FileRenamed
import org.plukh.mkvtool.core.FileTitled
import org.plukh.mkvtool.core.FixRun
import org.plukh.mkvtool.core.FontUsageReport
import org.plukh.mkvtool.core.PropeditRun
import org.plukh.mkvtool.core.RenamePlan
import org.plukh.mkvtool.core.RenameRun
import org.plukh.mkvtool.core.ShowFetched
import org.plukh.mkvtool.core.ShowNameResolved
import org.plukh.mkvtool.core.TitleRun
import org.plukh.mkvtool.core.TranslationFallback
import org.plukh.mkvtool.core.TrackSelection
import org.plukh.mkvtool.core.buildCheckReport
import org.plukh.mkvtool.out.RenderHints
import org.plukh.mkvtool.out.TextStyle
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The binding table is the whole reason a command cannot choose how its results look, so the thing worth
 * pinning is its completeness: every result type the application emits has a renderer filed under it. A
 * command whose result type is missing here would fail at run time on the first file it processed.
 */
class TextResultRenderersTest : FunSpec({

    test("every result type the application can emit is bound to a renderer") {
        textResultRenderers().registeredTypes shouldContainExactly setOf(
            FileConversion::class,
            ConversionRun::class,
            FileFix::class,
            FixRun::class,
            FileProped::class,
            PropeditRun::class,
            FileTitled::class,
            TitleRun::class,
            FontUsageReport::class,
            ShowFetched::class,
            TranslationFallback::class,
            EpisodeFetch::class,
            ShowNameResolved::class,
            RenamePlan::class,
            FileRenamed::class,
            RenameRun::class,
            CheckReport::class,
        )
    }

    test("a render hint reaches the renderer it is meant for") {
        // 9 deviating files out of 20, so the minority list is long enough to truncate — the one thing
        // verboseFileLists changes. Rendering through the registry is the point: it proves the hint
        // survives the trip from the command's options to the renderer's constructor.
        val infos = (1..20).map { i ->
            probed("e%02d.mkv".format(i), t(0, "video"), t(1, "audio", language = if (i <= 9) "rus" else "jpn"))
        }
        val report = buildCheckReport(infos, selection = TrackSelection.NONE, headerLabel = "Consistency check")

        render(report, RenderHints()) shouldContain "... and 1 more"
        render(report, RenderHints(verboseFileLists = true)) shouldNotContain "... and"
    }
})

private fun render(report: CheckReport, hints: RenderHints): String {
    val buffer = ByteArrayOutputStream()
    val stream = PrintStream(buffer, true, Charsets.UTF_8)
    textResultRenderers(hints).render(report, TextStyle(colorEnabled = false, out = stream, err = stream))
    return buffer.toString(Charsets.UTF_8)
}
