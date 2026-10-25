package org.plukh.mkvtool.cli.render

import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import org.plukh.mkvtool.core.BindingProblem
import org.plukh.mkvtool.core.BindingReport
import org.plukh.mkvtool.core.BindingResult
import org.plukh.mkvtool.core.BindingRule
import org.plukh.mkvtool.core.BoundFile
import org.plukh.mkvtool.core.DuplicateEpisode
import org.plukh.mkvtool.core.MainEpisode
import org.plukh.mkvtool.core.NameTie
import org.plukh.mkvtool.core.SeasonConflict
import org.plukh.mkvtool.core.SeasonUnknown
import org.plukh.mkvtool.core.SequenceCount
import org.plukh.mkvtool.core.SharedFile
import org.plukh.mkvtool.core.SharedMain
import org.plukh.mkvtool.core.SourceBinding
import org.plukh.mkvtool.core.TreeEntry
import org.plukh.mkvtool.core.UnpairedFile
import org.plukh.mkvtool.core.extensionOf
import java.io.File

/**
 * A batch's binding as `mux` and `inspect` both print it: the per-episode table under the render hint, and
 * every problem — an error where the command refuses the batch, a warning where it
 * carries on. The table is stdout; warnings and problems are stderr.
 */
class BindingRendererTest : FunSpec({

    context("the table") {
        test("one row per main, its sources' files beneath it, and READ where the map changed a number") {
            val first = File("[Grp] Show - 13 (BD).mkv")
            val second = File("[Grp] Show - 14 (BD).mkv")
            val binding = binding(
                mains = listOf(
                    MainEpisode(first, 13, 1),
                    MainEpisode(second, 14, 2),
                    MainEpisode(File("NCOP.mkv"), null, null),
                ),
                sources = listOf(
                    SourceBinding(
                        "additionalSources[0]",
                        listOf(
                            BoundFile(entry("Subs/13.srt"), first, BindingRule.REGEX),
                            BoundFile(entry("Subs/14.srt"), second, BindingRule.REGEX),
                        ),
                    ),
                    SourceBinding(
                        "additionalSources[1]",
                        listOf(BoundFile(entry("Rus/[Grp] Show - 13 (BD).mka"), first, BindingRule.SUFFIX)),
                    ),
                ),
                rule = BindingRule.REGEX,
            )

            val (out, err) = render(binding, table = true)

            out shouldBe "*** Binding: 3 main files, season 01, by episodeRegex\n" +
                "  EP   READ  MAIN\n" +
                "  01   13    [Grp] Show - 13 (BD).mkv\n" +
                "             additionalSources[0]  Subs/13.srt  (regex)\n" +
                "             additionalSources[1]  Rus/[Grp] Show - 13 (BD).mka  (suffix)\n" +
                "  02   14    [Grp] Show - 14 (BD).mkv\n" +
                "             additionalSources[0]  Subs/14.srt  (regex)\n" +
                "  -    -     NCOP.mkv\n" +
                "\n"
            err shouldBe ""
        }

        test("with no map at work there is no READ column, and a VobSub pair is one line") {
            val main = File("Show - S01E01.mkv")
            val binding = binding(
                mains = listOf(MainEpisode(main, 1, 1)),
                sources = listOf(
                    SourceBinding(
                        "additionalSources[0]",
                        listOf(
                            BoundFile(
                                entry("Subs/Show - S01E01.idx"),
                                main,
                                BindingRule.EXACT,
                                entry("Subs/Show - S01E01.sub"),
                            ),
                        ),
                    ),
                ),
            )

            render(binding, table = true).first shouldBe "*** Binding: 1 main file, season 01, by SxxEyy\n" +
                "  EP   MAIN\n" +
                "  01   Show - S01E01.mkv\n" +
                "       additionalSources[0]  Subs/Show - S01E01.idx + Show - S01E01.sub  (exact)\n" +
                "\n"
        }

        test("without the hint a clean binding says nothing at all") {
            val binding = binding(mains = listOf(MainEpisode(File("Show - S01E01.mkv"), 1, 1)))

            render(binding) shouldBe ("" to "")
        }
    }

    test("a main with no episode is a row of its own, and the header names the run's rule, not a main's") {
        val binding = binding(mains = listOf(MainEpisode(File("NCOP.mkv"), null, null)), rule = BindingRule.REGEX)

        render(binding, table = true) shouldBe (
            "*** Binding: 1 main file, season 01, by episodeRegex\n" +
                "  EP   MAIN\n" +
                "  -    NCOP.mkv\n" +
                "\n" to ""
        )
        // Without the table nothing is said: an episode only matters where a template asks for one.
        render(binding) shouldBe ("" to "")
    }

    context("each problem is a line under the error, naming the files and the source") {
        withData(
            nameFn = { it.second },
            SeasonUnknown to "no season: no file name and no episode metadata names one",
            SeasonConflict(mapOf("01" to listOf("a.mkv"), "02" to listOf("b.mkv")), "01") to
                "more than one season; the episode metadata says 01:\n" +
                "        season 01:\n          a.mkv\n        season 02:\n          b.mkv",
            DuplicateEpisode(3, listOf(File("a.mkv"), File("b.mkv"))) to
                "episode 3 is claimed by 2 main files: a.mkv, b.mkv",
            SequenceCount("general", 11, 12) to
                "general.matcher is sequence over 11 main files, but the episode metadata lists 12",
            SequenceCount("general", 1, 12) to
                "general.matcher is sequence over 1 main file, but the episode metadata lists 12",
            SequenceCount("general", 11, null) to
                "general.matcher is sequence over 11 main files, but there is no episode metadata to count against",
            SequenceCount("additionalSources[0]", 5, 1) to
                "additionalSources[0].matcher is sequence over 5 files, but the main files read 1 episode",
            SequenceCount("additionalSources[0]", 1, 2) to
                "additionalSources[0].matcher is sequence over 1 file, but the main files read 2 episodes",
            UnpairedFile("additionalSources[0]", entry("Subs/x.srt")) to
                "additionalSources[0]: Subs/x.srt pairs with no main file",
            NameTie("additionalSources[0]", entry("X.srt"), listOf(File("X.mkv"), File("X.mp4"))) to
                "additionalSources[0]: X.srt is named after more than one main file: X.mkv, X.mp4",
            SharedMain("additionalSources[0]", File("A.mkv"), listOf(entry("a.srt"), entry("b.srt"))) to
                "additionalSources[0]: more than one file pairs with A.mkv: a.srt, b.srt",
            SharedFile(entry("Subs/x.srt"), listOf("additionalSources[0]", "additionalSources[1]")) to
                "Subs/x.srt is matched by more than one source: additionalSources[0], additionalSources[1]",
        ) { (problem, lines) ->
            val (out, err) = render(binding(problems = listOf(problem)))

            out shouldBe ""
            err shouldBe "*** Error: the files cannot be bound to episodes (1 problem):\n      $lines\n"
        }
    }

    test("where the command carries on, the same problems are a warning") {
        val (_, err) = render(binding(problems = listOf(SeasonUnknown)), fatal = false)

        err shouldBe "*** Warning: the files cannot be bound to episodes (1 problem):\n" +
            "      no season: no file name and no episode metadata names one\n"
    }
})

private fun binding(
    mains: List<MainEpisode> = emptyList(),
    sources: List<SourceBinding> = emptyList(),
    problems: List<BindingProblem> = emptyList(),
    rule: BindingRule = BindingRule.SXXEYY,
) = BindingResult("01", rule, mains, sources, problems)

private fun render(binding: BindingResult, table: Boolean = false, fatal: Boolean = true): Pair<String, String> {
    val (out, err) = renderResult(BindingReportRenderer(table), BindingReport(binding, fatal))
    return out.replace("\r\n", "\n") to err.replace("\r\n", "\n")
}

private fun entry(relPath: String): TreeEntry {
    val name = relPath.substringAfterLast('/')
    val dir = relPath.substringBeforeLast('/', "")
    val base = name.substringBeforeLast('.')
    return TreeEntry(File(relPath), relPath, dir, dir.ifEmpty { null }, base, extensionOf(name))
}
